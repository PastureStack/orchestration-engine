package io.cattle.platform.iaas.api.auth.projects;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.object.impl.JooqObjectManager;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.postinit.SetPropertiesPostInstantiationHandler;
import io.cattle.platform.object.postinit.SpecialFieldsPostInstantiationHandler;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.AbstractSchemaPostProcessor;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.impl.DefaultConfiguration;
import org.junit.After;
import org.junit.Test;

/** Real validation/defaults and Jooq construction/post-init; only database IO is substituted. */
public class ProjectCreationLifecycleTest {
    @After public void cleanup() {
        ApiContext.remove();
        assertNull(ProjectCreationScope.currentProjectId());
    }

    @Test public void inheritedUserDefaultCreatesProjectBeforeFirstScheduleForSessionAndKey() throws Exception {
        for (boolean key : List.of(false, true)) {
            Fixture f = new Fixture();
            ApiRequest request = f.request(Map.of("name", "new project"), key);
            assertEquals("The native account schema supplies this inherited default", "user",
                    ((Map<?, ?>) request.getRequestObject()).get("kind"));
            Account result = (Account) f.manager.createThroughManager("project", request);
            assertEquals(Long.valueOf(9), result.getId());
            assertEquals("project", result.getKind());
            assertEquals(List.of("instantiate", "insert", "schedule", "reload", "persist", "members", "reload"), f.steps);
            assertEquals("The original input was not rewritten", "user", ((Map<?, ?>) request.getRequestObject()).get("kind"));
            assertEquals(1, f.ownerAssignments);
            assertNull(ProjectCreationScope.currentProjectId());
        }
    }

    @Test public void projectEndpointCannotCreateAnotherAccountKindOrUseAnInputId() throws Exception {
        for (String kind : List.of("user", "admin", "service")) {
            Fixture f = new Fixture();
            ApiRequest request = f.request(Map.of("name", "project", "kind", kind, "id", "88"), false);
            assertFalse(((Map<?, ?>) request.getRequestObject()).containsKey("id"));
            Account result = (Account) f.manager.createThroughManager("project", request);
            assertEquals("project", result.getKind());
            assertEquals(Long.valueOf(9), result.getId());
        }
    }

    @Test public void missingGeneratedIdAndUnexpectedPersistedKindStillFailBeforeScheduling() throws Exception {
        for (boolean missingId : List.of(false, true)) {
            Fixture f = new Fixture(); f.objects.missingId = missingId; f.objects.corruptKind = !missingId;
            ApiRequest request = f.request(Map.of("name", "project"), false);
            assertThrows(IllegalStateException.class, () -> f.manager.createThroughManager("project", request));
            assertEquals(List.of("instantiate", "insert"), f.steps);
            assertEquals(0, f.ownerAssignments);
            assertNull(ProjectCreationScope.currentProjectId());
        }
    }

    @Test public void schedulingFailureRestoresNestedScopeAndNeverAssignsMembers() throws Exception {
        Fixture f = new Fixture(); f.scheduleFailure = true;
        ApiRequest request = f.request(Map.of("name", "project"), false);
        try (ProjectCreationScope outer = ProjectCreationScope.open()) {
            AccountRecord existing = new AccountRecord(); existing.setId(77L); existing.setKind("project");
            ProjectCreationScope.bindCreated(existing);
            assertThrows(IllegalStateException.class, () -> f.manager.createThroughManager("project", request));
            assertEquals(Long.valueOf(77), ProjectCreationScope.currentProjectId());
        }
        assertEquals(List.of("instantiate", "insert", "schedule"), f.steps);
        assertEquals(0, f.ownerAssignments);
    }

    @Test public void projectContextAndOtherResourceTypeAreRejectedWithoutCreatingRows() throws Exception {
        Fixture f = new Fixture();
        ApiRequest request = f.request(Map.of("name", "project"), false);
        assertNull(f.manager.createThroughManager("account", request));
        f.actor.setKind("project");
        assertEquals(403, assertThrows(ClientVisibleException.class, () -> f.manager.createThroughManager("project", request)).getStatus());
        assertTrue(f.steps.isEmpty());
    }

    private static final class Fixture {
        final List<String> steps = new ArrayList<>();
        final AccountRecord actor = new AccountRecord();
        final AuthDao auth = mock(AuthDao.class);
        final ObjectProcessManager processes = mock(ObjectProcessManager.class);
        final SchemaFactoryImpl schemas = new SchemaFactoryImpl();
        final DatabaseIoOnly objects = new DatabaseIoOnly(steps);
        final ExposedManager manager = new ExposedManager();
        final ProjectMemberResourceManager members = new ProjectMemberResourceManager();
        int ownerAssignments;
        boolean scheduleFailure;

        Fixture() throws Exception {
            schemas.setId("native-project-lifecycle");
            schemas.setPostProcessors(List.of(new AbstractSchemaPostProcessor() {
                @Override public SchemaImpl postProcessRegister(SchemaImpl schema, SchemaFactory parent) {
                    if (schema.getId().endsWith("Record")) schema.setId(schema.getId().substring(0, schema.getId().length() - 6));
                    return schema;
                }
            }));
            schemas.setTypes(List.of(AccountRecord.class));
            schemas.setTypeNames(List.of("project,parent=account")); schemas.init();
            SchemaImpl project = (SchemaImpl) schemas.getSchema("project");
            project.setCollectionMethods(List.of("POST"));
            Path root = Path.of("").toAbsolutePath();
            while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/base/account.json"))) root = root.getParent();
            assertNotNull(root);
            String inheritedDefault = new ObjectMapper().readTree(root.resolve("resources/content/schema/base/account.json").toFile())
                    .path("resourceFields").path("kind").path("default").asText();
            FieldImpl kind = new FieldImpl(); kind.setName("kind"); kind.setType("string"); kind.setCreate(true); kind.setDefault(inheritedDefault);
            project.getResourceFields().put("kind", kind);
            FieldImpl name = new FieldImpl(); name.setName("name"); name.setType("string"); name.setCreate(true);
            project.getResourceFields().put("name", name);
            objects.setSchemaFactory(schemas); objects.setConfiguration(new DefaultConfiguration());
            ObjectMetaDataManager metadata = mock(ObjectMetaDataManager.class);
            when(metadata.convertToPropertyNameString(eq(AccountRecord.class), any())).thenAnswer(call -> call.getArgument(1));
            objects.setMetaDataManager(metadata);
            SpecialFieldsPostInstantiationHandler defaults = new SpecialFieldsPostInstantiationHandler(); defaults.setSchemaFactory(schemas);
            objects.setPostInitHandlers(List.of(defaults, new SetPropertiesPostInstantiationHandler()));
            actor.setId(7L); actor.setKind("user"); actor.setState("active");
            when(auth.getAccountById(7L)).thenReturn(actor);
            members.authDao = auth;
            when(auth.setProjectMembers(any(Account.class), anySet(), any())).thenAnswer(call -> {
                Account created = call.getArgument(0);
                assertEquals(Long.valueOf(9), created.getId()); assertEquals("project", created.getKind());
                assertEquals(Long.valueOf(9), ProjectCreationScope.currentProjectId());
                Set<Member> owners = call.getArgument(1);
                assertEquals(Set.of(new Member(new Identity("rancher_id", "7"), "owner")), owners);
                steps.add("members"); ownerAssignments++; return List.of();
            });
            doAnswer(call -> {
                Account created = call.getArgument(1);
                assertEquals(Long.valueOf(9), created.getId()); assertEquals("project", created.getKind());
                assertEquals("project", ((Map<?, ?>) call.getArgument(2)).get("kind"));
                assertEquals(Long.valueOf(9), ProjectCreationScope.currentProjectId());
                steps.add("schedule");
                if (scheduleFailure) throw new IllegalStateException("test schedule failure");
                return null;
            }).when(processes).scheduleStandardProcess(eq(StandardProcess.CREATE), any(Account.class), anyMap());
            manager.setObjectManager(objects); manager.setObjectProcessManager(processes);
            manager.objectManager = objects; manager.authDao = auth; manager.projectMemberResourceManager = members;
        }

        ApiRequest request(Map<String, Object> input, boolean key) throws Exception {
            ApiRequest request = new ApiRequest(null, schemas);
            request.setType("project"); request.setMethod("POST"); request.setRequestObject(new HashMap<>(input));
            Policy policy = new AccountPolicy(actor, actor, Set.of(new Identity("rancher_id", "7")), new NoPolicyOptions());
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            ApiContext context = ApiContext.newContext(); context.setApiRequest(request); context.setPolicy(policy); context.setIdFormatter(formatter);
            if (key) ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 7, "apiKey", 1,
                    new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of())));
            ValidationHandler validation = new ValidationHandler(); validation.init(); validation.handle(request);
            return request;
        }
    }

    private static final class ExposedManager extends ProjectResourceManager {
        Object createThroughManager(String type, ApiRequest request) { return super.createInternal(type, request); }
    }

    private static final class DatabaseIoOnly extends JooqObjectManager {
        final List<String> steps;
        boolean missingId, corruptKind;
        DatabaseIoOnly(List<String> steps) { this.steps = steps; }
        @Override public <T> T instantiate(Class<T> type, Map<String, Object> properties) {
            T record = super.instantiate(type, properties);
            assertTrue(record instanceof AccountRecord);
            assertNull("A constructed model has no preallocated database ID", ((Account) record).getId());
            assertNull("Post-initialization has not populated kind yet", ((Account) record).getKind());
            assertNull(ProjectCreationScope.currentProjectId()); steps.add("instantiate"); return record;
        }
        @Override public <T> T insert(T instance, Class<?> type, Map<String, Object> properties) {
            Account account = (Account) instance;
            assertNull("Only the database insert assigns identity", account.getId());
            steps.add("insert");
            if (!missingId) account.setId(9L);
            if (corruptKind) account.setKind("service");
            return instance;
        }
        @Override public <T> T reload(T instance) { steps.add("reload"); return instance; }
        @Override public <T> T persist(T instance) { steps.add("persist"); return instance; }
    }
}
