package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiCookieAuthentication;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.framework.secret.SecretsService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Test;

public class AiCatalogLaunchWorkflowTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void privateReferenceIdsStayOutsideNativeGraphAndChangedIdsRejectConfirmation() throws Exception {
        Fixture f = new Fixture(false);
        when(f.service.materializer.prepare(any(), any(), any(), any(), any())).thenReturn(
                new AiCatalogLaunchMaterializer.PreparedGraph(f.preparedNativeGraph, Map.of("db-secret", "1se7")));
        Map<String, Object> preview = f.preview();
        Map<String, Object> values = f.service.privateData.open(f.store.plan);
        assertEquals(Map.of("db-secret", "1se7"), values.get("authorizedReferenceIds"));
        assertFalse(AiCatalogLaunchInput.map(values.get("graph")).containsKey("resolvedSecrets"));
        assertFalse(AiCatalogLaunchInput.map(values.get("graph")).containsKey("authorizedReferenceIds"));
        assertEquals(f.service.graphs.digest(Map.of("nativeGraph", values.get("graph"), "authorizedReferenceIds",
                values.get("authorizedReferenceIds"))), values.get("graphDigest"));
        assertFalse(f.json.writeValueAsString(preview).contains("1se7"));
        when(f.service.materializer.prepare(any(), any(), any(), any(), any())).thenReturn(
                new AiCatalogLaunchMaterializer.PreparedGraph(f.preparedNativeGraph, Map.of("db-secret", "1se8")));
        assertEquals("CatalogLaunchSourceChanged", assertThrows(ClientVisibleException.class,
                () -> f.action("confirm", Map.of())).getCode());
        assertEquals("pending", f.store.plan.get("confirmationState")); assertEquals(0, f.store.claims); verifyNoInteractions(f.manager);
    }

    @Test public void humanConfirmsFrozenPlanNormalManagerCreatesExactlyOnceAndPublicWireContainsNoPrivateValues() throws Exception {
        Fixture f = new Fixture(false); Map<String, Object> preview = f.preview();
        assertEquals("needs_user", preview.get("status")); assertEquals(true, preview.get("inputsReady"));
        assertEquals(0, f.store.claims); verifyNoInteractions(f.manager);
        f.action("confirm", Map.of()); assertEquals("approved", f.store.plan.get("confirmationState"));
        Map<String, Object> accepted = f.action("apply", Map.of());
        assertEquals("accepted", accepted.get("status")); assertEquals(1, f.store.claims);
        assertEquals("1st889", AiCatalogLaunchInput.map(accepted.get("createdResource")).get("id"));
        assertEquals("accepted", f.action("apply", Map.of()).get("status"));
        verify(f.manager, times(1)).create(eq("stack"), any());
        verify(f.service.observation).visible(eq(f.store.plan), eq(f.request));
        String wire = f.json.writeValueAsString(accepted);
        for (String privateValue : List.of("privateEnvelope", "private-compose", "private-source", "delegationProof", "humanAnswers", "safeAnswers"))
            assertFalse(privateValue, wire.contains(privateValue));
        Map<String, Object> roundTrip = f.json.readValue(wire);
        for (Object value : (List<?>) roundTrip.get("changes")) {
            Map<String, Object> change = AiCatalogLaunchInput.map(value); assertTrue(change.containsKey("before")); assertNull(change.get("before"));
        }
        assertSame(f.request, ApiContext.getContext().getApiRequest()); assertSame(f.actor, ApiContext.getContext().getPolicy());
    }

    @Test public void missingRoleActionAndChangedSourceRejectBeforePersistenceOrClaim() throws Exception {
        Fixture denied = new Fixture(false); denied.serviceSchema.setResourceActions(Map.of("activate", new Action(null, null)));
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class, denied::preview).getCode());
        assertNull(denied.store.plan); assertEquals(0, denied.store.claims); verifyNoInteractions(denied.manager);
        Fixture changed = new Fixture(false); changed.preview(); changed.digest = "b".repeat(64);
        assertEquals("CatalogLaunchSourceChanged", assertThrows(ClientVisibleException.class, () -> changed.action("confirm", Map.of())).getCode());
        assertEquals("pending", changed.store.plan.get("confirmationState")); assertEquals(0, changed.store.claims); verifyNoInteractions(changed.manager);
    }

    @Test public void optionalHumanInputRequiresExplicitEmptyAndOwnerVariablesCannotFallBackToSourceDefaults() throws Exception {
        Fixture f = new Fixture(true); Map<String, Object> preview = f.preview();
        assertEquals(false, preview.get("inputsReady")); assertEquals(1, ((List<?>) preview.get("missingHumanInputs")).size());
        assertEquals("CatalogLaunchNeedsHumanInputs", assertThrows(ClientVisibleException.class, () -> f.action("confirm", Map.of())).getCode());
        Map<String, Object> completed = f.action("completeInputs", Map.of("answers", Map.of("password", ""), "referenceBindings", Map.of()));
        assertEquals(true, completed.get("inputsReady")); assertEquals("", AiCatalogLaunchInput.map(f.lastValidation.get("humanAnswers")).get("password"));
        assertFalse(f.json.writeValueAsString(completed).contains("source-private-default"));
        assertEquals("", AiCatalogLaunchInput.variables(Map.of("password", ""), f.questions).get("password"));
        assertThrows(ClientVisibleException.class, () -> AiCatalogLaunchInput.variables(Map.of(), f.questions));
        assertThrows(ClientVisibleException.class, () -> AiCatalogLaunchInput.variables(Map.of("password", false), f.questions));
        assertThrows(ClientVisibleException.class, () -> AiCatalogLaunchInput.variables(Map.of("password", "", "foreign", "extra"), f.questions));
    }

    @Test public void actualHttpResourceMapperPreservesNullChangeKeysWithoutSerializingPrivatePlanState() throws Exception {
        Fixture f = new Fixture(false); Map<String, Object> preview = f.preview();
        var resource = new io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl((String) preview.get("id"), AiCatalogLaunchInput.PLAN, preview);
        var mapper = new io.github.ibuildthecloud.gdapi.json.JacksonMapper(); mapper.init();
        var bytes = new java.io.ByteArrayOutputStream(); mapper.writeValue(bytes, resource);
        Map<String, Object> wire = AiCatalogLaunchInput.map(mapper.readValue(bytes.toByteArray()));
        assertTrue(wire.containsKey("createdResource")); assertNull(wire.get("createdResource"));
        for (Object value : (List<?>) wire.get("changes")) {
            Map<String, Object> change = AiCatalogLaunchInput.map(value);
            assertTrue(change.containsKey("before")); assertNull(change.get("before")); assertTrue(change.containsKey("after"));
        }
        assertFalse(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("privateEnvelope"));
        assertFalse(bytes.toString(java.nio.charset.StandardCharsets.UTF_8).contains("private-compose"));
    }

    @Test public void missingRequiredReadRejectsPreviewConfirmAndApplyBeforeAnyClaimOrDispatch() throws Exception {
        Fixture f = new Fixture(false);
        SchemaImpl maps = (SchemaImpl) f.schemas.getSchema("serviceConsumeMap"); maps.setCollectionMethods(List.of());
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class, f::preview).getCode()); assertNull(f.store.plan);
        maps.setCollectionMethods(List.of("GET")); f.preview(); maps.setCollectionMethods(List.of());
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class, () -> f.action("confirm", Map.of())).getCode());
        maps.setCollectionMethods(List.of("GET")); f.action("confirm", Map.of()); maps.setCollectionMethods(List.of());
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class, () -> f.action("apply", Map.of())).getCode());
        assertEquals(0, f.store.claims); verifyNoInteractions(f.manager);
    }

    @Test public void independentOptionsAdvertiseReadCreateUpdateWithoutGrantingLaunchPermission() throws Exception {
        Fixture f = new Fixture(false); SchemaImpl options = new SchemaImpl(); options.setId(AiCatalogLaunchInput.OPTIONS);
        options.setCollectionMethods(List.of("POST")); when(f.schemas.getSchema(AiCatalogLaunchInput.OPTIONS)).thenReturn(options);
        ((SchemaImpl) f.schemas.getSchema("stack")).setCollectionMethods(List.of());
        AiCatalogLaunchOptionsService service = new AiCatalogLaunchOptionsService(); service.access = f.service.access;
        service.gateway = f.service.gateway; service.snapshots = f.service.options; service.plans = f.store;
        service.auditSinks = List.of(mock(ApiKeyAuditSink.class));
        f.request.setRequestObject(Map.of("templateId", f.template, "versionId", f.version));
        assertEquals(List.of("read", "create", "update"), service.read(f.request).get("requiredOperations"));
        assertNull(f.store.plan); assertEquals(0, f.store.claims); verifyNoInteractions(f.manager);
    }

    private static final class Store extends AiCatalogLaunchPlanStore {
        Map<String, Object> plan; int claims;
        @Override public <T> T transaction(Supplier<T> action) { return action.get(); }
        @Override public void insert(Map<String, Object> value) { plan = value; }
        @Override public Map<String, Object> loadForUpdate(String id) { return plan != null && id.equals(plan.get("id")) ? plan : null; }
        @Override public void save(Map<String, Object> value) { plan = value; }
        @Override public boolean claim(Map<String, Object> value) { if (claims != 0) return false; claims++; return true; }
        @Override public int pendingCount(long actor, long now) { return plan == null ? 0 : 1; }
    }

    private static final class Fixture {
        final JacksonJsonMapper json = new JacksonJsonMapper(); final Policy actor = mock(Policy.class);
        final SchemaFactory schemas = mock(SchemaFactory.class); final SchemaImpl serviceSchema = new SchemaImpl();
        final ResourceManager manager = mock(ResourceManager.class); final ApiRequest request; final Store store = new Store();
        final AiCatalogLaunchPlanService service = new AiCatalogLaunchPlanService() { @Override protected void enabled() { } };
        final String template = "ctm-" + "a".repeat(64), version = "cvm-" + "b".repeat(64), optionsRef;
        final List<Map<String, Object>> questions; String digest = "a".repeat(64); Map<String, Object> lastValidation, preparedNativeGraph;
        Fixture(boolean human) throws Exception {
            when(actor.getAuthenticatedAsAccountId()).thenReturn(42L); when(actor.getAccountId()).thenReturn(5L);
            SchemaImpl plan = new SchemaImpl(); plan.setId(AiCatalogLaunchInput.PLAN); plan.setCollectionMethods(List.of("GET", "POST"));
            plan.setResourceMethods(List.of("GET")); plan.setResourceActions(Map.of("completeInputs", new Action(null, null),
                    "confirm", new Action(null, null), "apply", new Action(null, null), "cancel", new Action(null, null)));
            when(schemas.getSchema(AiCatalogLaunchInput.PLAN)).thenReturn(plan);
            SchemaImpl stack = new SchemaImpl(); stack.setId("stack"); stack.setCollectionMethods(List.of("POST")); stack.setResourceMethods(List.of("GET"));
            for (String field : List.of("name", "description", "externalId", "dockerCompose", "rancherCompose")) field(stack, field, "string");
            field(stack, "environment", "map[string]"); field(stack, "startOnCreate", "boolean"); when(schemas.getSchema("stack")).thenReturn(stack);
            serviceSchema.setId("service"); serviceSchema.setResourceActions(Map.of("setservicelinks", new Action(null, null), "activate", new
                    Action(null, null)));
            when(schemas.getSchema("service")).thenReturn(serviceSchema);
            serviceSchema.setCollectionMethods(List.of("GET", "POST")); serviceSchema.setResourceMethods(List.of("GET"));
            SchemaImpl project = new SchemaImpl(); project.setId("project"); project.setResourceMethods(List.of("GET"));
            when(schemas.getSchema("project")).thenReturn(project);
            for (String type : List.of("schema", "serviceConsumeMap")) {
                SchemaImpl read = new SchemaImpl(); read.setId(type); read.setCollectionMethods(List.of("GET")); when(schemas.getSchema(type)).thenReturn(read);
            }
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
                    formatter.setTypeMappings(Map.of("stack", "st", "account", "a"));
            ApiServletContext servlet = mock(ApiServletContext.class); HttpServletRequest http = mock(HttpServletRequest.class);
            when(servlet.getRequest()).thenReturn(http); when(http.getCookies()).thenReturn(new Cookie[] { new Cookie("token", "verified-cookie") });
            request = new ApiRequest(servlet, schemas); request.setType(AiCatalogLaunchInput.PLAN); request.setMethod("POST");
                    request.setRequestParams(Map.of());
            request.setAttribute(AccountConstants.AUTH_TYPE, "TokenAuth"); ApiCookieAuthentication.verified(request, "verified-cookie");
            ApiContext.newContext().setApiRequest(request); ApiContext.getContext().setPolicy(actor); ApiContext.getContext().setIdFormatter(formatter);
            var authority = new AiCatalogLaunchAccess.Authorization(new ApiAuthenticator.CurrentAuthorization(actor, schemas), null);
            service.access = new AiCatalogLaunchAccess() {
                @Override public Policy actor(ApiRequest request) { return actor; }
                @Override public Authorization bound(Map<String, Object> plan, ApiRequest request) { return authority; }
            };
            service.access.idFormatter = formatter; service.access.keyAccess = mock(ApiKeyAuthorizationService.class);
            service.access.validation = new ValidationHandler(); service.access.validation.setSupportedMethods(Set.of("GET", "POST", "PUT", "DELETE"));
            service.access.managers = mock(ResourceManagerLocator.class); when(service.access.managers.getResourceManagerByType("stack")).thenReturn(manager);
            service.store = store; service.options = new AiCatalogLaunchOptionsStore(); service.graphs = new AiCatalogLaunchGraph(); service.graphs.json = json;
            service.privateData = new AiCatalogLaunchPrivate(); service.privateData.json = json; service.privateData.secrets = mock(SecretsService.class);
            Map<String, String> encrypted = new LinkedHashMap<>();
            when(service.privateData.secrets.encrypt(anyLong(), anyString())).thenAnswer(call -> {
                String key = "encrypted-" + UUID.randomUUID(); encrypted.put(key, call.getArgument(1)); return key;
            });
            when(service.privateData.secrets.decrypt(anyLong(), anyString())).thenAnswer(call -> encrypted.get(call.getArgument(1)));
            questions = human ? List.of(Map.of("name", "password", "type", "human", "required", false, "requiresHuman", true,
                    "label", "Password", "description", "", "sourceType", "password")) : List.of();
            service.gateway = new AiCatalogLaunchGateway() {
                @Override public Map<String, Object> material(String p, String t, String v) {
                    return Map.of("digest", digest, "questionSchema", questions, "externalId", "catalog://fixture:latest",
                            "template", Map.of("templateKey", "app", "name", "App", "catalogScope", "global", "catalogId", "fixture"),
                            "version", Map.of("version", "1", "revision", 1));
                }
                @Override public Map<String, Object> validate(String project, Map<String, Object> input) {
                    lastValidation = input;
                    boolean ready = !human || AiCatalogLaunchInput.map(input.get("humanAnswers")).containsKey("password");
                    return Map.of("digest", digest, "missingHumanInputs", ready ? List.of() : questions,
                            "variables", human ? Map.of("password", "") : Map.of());
                }
            };
            service.materializer = mock(AiCatalogLaunchMaterializer.class);
            preparedNativeGraph = Map.of("services", List.of(Map.of("name", "app")),
                    "volumes", List.of(), "dockerCompose", "# notemplating\nprivate-compose", "rancherCompose", "# notemplating\n",
                    "variables", human ? Map.of("password", "") : Map.of());
            when(service.materializer.prepare(any(), any(), any(), any(), any())).thenReturn(
                    new AiCatalogLaunchMaterializer.PreparedGraph(preparedNativeGraph, Map.of()));
            service.binding = new AiCatalogLaunchBinding(); service.binding.store = store; service.observation = mock(AiCatalogLaunchObservation.class);
            service.locks = mock(LockManager.class); when(service.locks.lock(any(), any())).thenAnswer(call -> ((LockCallback<?>)
                    call.getArgument(1)).doWithLock());
            service.auditSinks = List.of(mock(ApiKeyAuditSink.class));
            when(manager.create(eq("stack"), any())).thenAnswer(call -> {
                ApiRequest domain = call.getArgument(1); assertEquals("POST", domain.getMethod()); assertTrue(AiCatalogLaunchAccess.trustedDomain(domain));
                assertSame(domain, ApiContext.getContext().getApiRequest()); assertSame(request.getServletContext(), domain.getServletContext());
                StackRecord created = new StackRecord(); created.setId(889L); created.setAccountId(5L); created.setKind("stack"); created.setName("chosen");
                return service.binding.onCreate(created, io.cattle.platform.core.model.Stack.class, Map.of());
            });
            optionsRef = service.options.put(AiCatalogLaunchOptionsStore.binding(request, actor), template, version, digest, questions);
        }
        Map<String, Object> preview() {
            request.setRequestObject(Map.of("templateId", template, "versionId", version, "name", "chosen", "description", "",
                    "startOnCreate", true, "optionsRef", optionsRef, "answers", Map.of(), "referenceBindings", Map.of()));
            return service.preview(request);
        }
        Map<String, Object> action(String action, Map<String, Object> input) {
            request.setAction(action); request.setRequestObject(input); return service.action((String) store.plan.get("id"), request);
        }
        private static void field(SchemaImpl schema, String name, String type) {
            FieldImpl field = new FieldImpl(); field.setType(type); field.setCreate(true); field.setNullable(true); schema.getResourceFields().put(name, field);
        }
    }
}
