package io.cattle.platform.iaas.api.auth.projects;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.apikey.*;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import java.time.Instant;
import java.util.*;
import org.junit.After;
import org.junit.Test;

public class NativeProjectQueuedOwnerTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void ordinaryNativeOwnerFullLegacyCustomAndLiveDenialsUseManagerCaptureWithoutAi() throws Exception {
        for (String mode : List.of("legacy", "full", "custom")) {
            Fixture f = new Fixture(mode); f.update(); f.detach(); f.hook.beforeExecution(f.record, f.metadata);
            assertNull("No request grant may authorize background work", f.fresh.authorizeObject(f.project));
            assertEquals(77L, f.metadata.get("accountId")); assertEquals(42L, f.metadata.get("principalAccountId"));
            assertEquals(List.of("name", "virtualMachine"), f.metadata.get("projectUpdateFields"));
            assertFalse(f.metadata.get("requestId").toString().startsWith("aichange-"));
            assertTrue(ProcessAuthorization.executionBound(f.record));
            when(f.dao.isProjectOwner(77L, 42L, false, Set.of())).thenReturn(false); f.denied("OwnerPermissionDenied");
            when(f.dao.isProjectOwner(77L, 42L, false, Set.of())).thenReturn(true);
            f.name.setUpdate(false); f.denied("OwnerPermissionDenied"); f.name.setUpdate(true);
            String uuid = f.project.getUuid(); f.project.setUuid("replaced"); f.denied("OwnerPermissionDenied"); f.project.setUuid(uuid);
            f.credential.setRemoved(new Date()); f.denied("ApiKeyRevoked"); f.credential.setRemoved(null);
            f.hook.beforeExecution(f.record, f.metadata);
            LaunchConfiguration changed = new LaunchConfiguration(f.record); changed.setData(new HashMap<>(changed.getData()));
            changed.getData().put("labels", Map.of("not", "captured"));
            assertThrows(ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(new ProcessRecord(changed, 992L, "unit"), f.metadata));
            var missing = new LaunchConfiguration(f.record); missing.setData(new HashMap<>(missing.getData()));
            missing.getData().remove(ProcessAuthorization.CAPTURED_EXECUTION_KEY);
            assertThrows(ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(new ProcessRecord(missing, 993L, "unit"), f.metadata));
        }
        Fixture readKey = new Fixture("read"); readKey.update(); readKey.detach(); readKey.denied("KeyScopeDenied");
        Fixture expired = new Fixture("expired"); expired.update(); expired.detach(); expired.denied("ApiKeyExpired");
        Fixture revised = new Fixture("full"); revised.update(); revised.detach();
        revised.credential.setData(new ApiKeyPolicyCodec().store(revised.credential, revised.policy, 4)); revised.denied("ApiKeyPolicyChanged");
        Fixture readonly = new Fixture("full"); when(readonly.dao.isProjectOwner(77L, 42L, false, Set.of())).thenReturn(false);
        assertThrows(ClientVisibleException.class, readonly::update); assertNull(readonly.record);
    }

    private static final class Manager extends ProjectResourceManager {
        Object update(AccountRecord project, ApiRequest request) { return updateInternal("project", "77", project, request); }
        @Override protected Resource createResource(Object obj, IdFormatter formatter, ApiRequest request) {
            Resource resource = mock(Resource.class); when(resource.getFields()).thenReturn(Map.of("name", "before", "virtualMachine", false));
            return resource;
        }
    }

    private static final class Fixture {
        final AccountRecord project = new AccountRecord(), principal = new AccountRecord();
        final CredentialRecord credential = new CredentialRecord();
        final AuthDao dao = mock(AuthDao.class); final AccountDao accounts = mock(AccountDao.class);
        final ObjectManager objects = mock(ObjectManager.class); final ObjectProcessManager processes = mock(ObjectProcessManager.class);
        final ApiAuthenticator authenticator = mock(ApiAuthenticator.class); final ApiKeyProcessAuthorization hook = new ApiKeyProcessAuthorization();
        final ProjectAccessService access = new ProjectAccessService(dao, accounts); final Manager manager = new Manager();
        final Schema schema = mock(Schema.class); final SchemaFactory schemas = mock(SchemaFactory.class);
        final FieldImpl name = new FieldImpl(), virtualMachine = new FieldImpl();
        final ApiRequest request; final Policy initial, fresh; final ApiKeyPolicy policy; ProcessRecord record; Map<String, Object> metadata;
        Fixture(String mode) throws Exception {
            principal.setId(42L); principal.setKind("user"); principal.setState("active");
            project.setId(77L); project.setKind("project"); project.setState("active"); project.setUuid("project-native-77"); project.setName("before");
            initial = new AccountPolicy(principal, principal, Set.of(), new NoPolicyOptions());
            fresh = new AccountPolicy(principal, principal, Set.of(), new NoPolicyOptions());
            assertFalse(fresh.isOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS)); assertNull(fresh.authorizeObject(project));
            when(dao.hasAccessToProject(77L, 42L, false, Set.of())).thenReturn(true);
            when(dao.isProjectOwner(77L, 42L, false, Set.of())).thenReturn(true); when(accounts.isActiveAccount(project)).thenReturn(true);
            when(schemas.getSchema("project")).thenReturn(schema); when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
            name.setType("string"); name.setUpdate(true); name.setAttributes(Map.of("scheduleUpdate", true));
            virtualMachine.setType("boolean"); virtualMachine.setUpdate(true); virtualMachine.setAttributes(Map.of("scheduleUpdate", true));
            var readonly = new FieldImpl(); readonly.setType("string"); readonly.setUpdate(false);
            when(schema.getResourceFields()).thenReturn(Map.of("name", name, "virtualMachine", virtualMachine, "uuid", readonly,
                    "state", readonly, "created", readonly));
            request = new ApiRequest(null, schemas); request.setSchemaFactory(schemas); request.setType("project"); request.setMethod("PUT");
            request.setId("1a77"); request.setRequestParams(Map.of());
            request.setRequestObject(Map.of("id", "1a999", "type", "project", "uuid", "must-not-write", "state", "removed",
                    "created", "untrusted-display-date", "links", Map.of("self", "display-only"), "name", "after", "virtualMachine", true));
            var context = ApiContext.newContext(); context.setApiRequest(request); context.setPolicy(initial);
            var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("project", "a", "account", "a", "credential", "c"));
            context.setIdFormatter(formatter);
            var resolver = new ApiKeyTargetResolver(); inject(resolver, "idFormatter", formatter);
            inject(resolver, "projectAccess", access); inject(resolver, "objectManager", objects);
            inject(hook, "targets", resolver); inject(hook, "projectAccess", access);
            inject(hook, "objectManager", objects); inject(hook, "authenticator", authenticator);
            when(objects.getSchemaFactory()).thenReturn(schemas); when(objects.getType(AccountRecord.class)).thenReturn("account");
            when(objects.loadResource("project", 77L)).thenReturn(project); when(objects.loadResource("account", "77")).thenReturn(project);
            when(objects.loadResource(Credential.class, 7L)).thenReturn(credential);
            when(authenticator.currentAuthorization(eq(42L), eq(42L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(fresh, schemas));
            when(objects.setFields(eq(schema), same(project), anyMap())).thenAnswer(call -> {
                Map<?, ?> updates = call.getArgument(2); assertEquals(Set.of("name", "virtualMachine"), updates.keySet());
                assertEquals("project-native-77", project.getUuid()); assertEquals("active", project.getState()); return project;
            });
            when(objects.reload(project)).thenReturn(project);
            manager.authDao = dao; manager.projectAccess = access; manager.setObjectManager(objects); manager.setObjectProcessManager(processes);
            credential.setId(7L); credential.setAccountId(42L); credential.setState("active");
            boolean legacy = mode.equals("legacy"); credential.setKind(legacy ? "apiKey" : "apiKeyRestricted");
            policy = legacy ? null : mode.equals("full") || mode.equals("expired") ? new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW,
                    mode.equals("expired") ? Instant.parse("2000-01-01T00:00:00Z") : null, List.of(), ApiKeyPolicy.Purpose.GENERAL)
                    : new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(new ApiKeyPolicy.Rule("exact-project",
                            ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("project", "1a77"),
                            mode.equals("read") ? Set.of("read") : Set.of("read", "update"))), ApiKeyPolicy.Purpose.GENERAL);
            credential.setData(legacy ? Map.of() : new ApiKeyPolicyCodec().store(credential, policy, 3));
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, credential.getKind(), legacy ? 0 : 3, policy));
            request.setAttribute("apiKey.audit.decision", "ALLOW"); request.setAttribute("apiKey.audit.keyId", "1c7");
            request.setAttribute("apiKey.audit.requestId", UUID.randomUUID().toString()); request.setAttribute("apiKey.audit.operation", "update");
            doAnswer(call -> {
                LaunchConfiguration config = new LaunchConfiguration("account.update", "account", "77", null, 0, call.getArgument(2));
                ProcessAuthorization.prepare(config, List.of(hook)); record = new ProcessRecord(config, 991L, "unit");
                metadata = ProcessAuthorization.metadata(record); return null;
            }).when(processes).scheduleStandardProcess(eq(StandardProcess.UPDATE), same(project), anyMap());
        }
        void update() throws java.io.IOException {
            var validation = new ValidationHandler(); validation.init(); validation.generate(request);
            assertEquals(Map.of("name", "after", "virtualMachine", true), request.getRequestObject()); assertEquals("77", request.getId());
            manager.listInternal(schemas, "project", Map.of("id", "77"), null);
            manager.update(project, request); assertNotNull(record);
        }
        void detach() { ApiContext.remove(); ApiContext.newContext().setPolicy(fresh); assertNull(fresh.authorizeObject(project)); }
        void denied(String code) {
            assertEquals(code, assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(record, metadata)).getCode());
        }
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(field); f.setAccessible(true); f.set(target, value);
    }
}
