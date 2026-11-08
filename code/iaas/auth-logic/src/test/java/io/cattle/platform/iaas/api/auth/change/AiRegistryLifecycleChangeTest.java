package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.model.tables.records.StoragePoolRecord;
import io.cattle.platform.iaas.api.auth.ai.view.AiResourceViewService;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiRegistryLifecycleChangeTest {
    @Test public void onlyEmptyRegistryStartAndStopReachTheNativeActions() {
        for (String operation : List.of("start", "stop")) {
            var descriptor = AiResourceChangeDescriptor.require("registry", operation);
            assertEquals("POST", descriptor.method()); assertEquals("update", descriptor.keyOperation());
            assertEquals(operation.equals("start") ? "activate" : "deactivate", descriptor.action());
            assertEquals(Map.of(), descriptor.parameters(Map.of())); assertEquals(Map.of(), descriptor.body(Map.of()));
            for (String field : List.of("serverAddress", "name", "secretValue", "credential", "registryId", "remove"))
                assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of(field, "PRIVATE")));
        }
        for (String operation : List.of("create", "update_metadata", "restart", "purge"))
            assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("registry", operation));
    }

    @Test public void addressProjectionIsExplicitAndNeverInventsANameOrExposesCredentialText() {
        for (String address : List.of("registry.example:5000", "quay.io", "[2001:db8::1]:5000")) {
            var fields = new LinkedHashMap<String, Object>(); fields.put("serverAddress", address);
            fields.put("data", Map.of("secretValue", "PRIVATE"));
            var safe = AiResourceViewService.project("registry", "1sp3", fields);
            assertEquals(address, safe.get("serverAddress")); assertEquals("known", safe.get("addressStatus"));
            assertFalse(safe.containsKey("name")); assertFalse(safe.toString().contains("PRIVATE"));
        }
        for (String unsafe : List.of("user:PRIVATE@registry.example", "registry.example?token=PRIVATE", "registry.example/#PRIVATE",
                "https://registry.example", "registry.example/path", "registry.example:0", "registry.example:65536",
                "registry.example\nPRIVATE", "registry.example%40PRIVATE")) {
            var safe = AiResourceViewService.project("registry", "1sp3", Map.of("serverAddress", unsafe));
            assertNull(safe.get("serverAddress")); assertEquals("unknown", safe.get("addressStatus"));
            assertFalse(safe.toString().contains("PRIVATE")); assertFalse(safe.containsKey("name"));
        }
        assertEquals("existing", AiResourceViewService.project("registry", "1sp3", Map.of("name", "existing")).get("name"));
        assertEquals("ordinary", AiResourceViewService.project("service", "1s3", Map.of("name", "ordinary")).get("name"));
    }

    @Test public void actualRegistryIdentityAndFullConfigurationArePreservedAcrossStateChanges() {
        var domain = new AiResourceChangeDomain(); domain.json = new io.cattle.platform.json.JacksonJsonMapper(); var pool = registry();
        var before = domain.snapshot(pool); assertEquals("registry-uuid", before.get("uuid"));
        var start = AiResourceChangeDescriptor.require("registry", "start");
        domain.ready(start, pool, null); pool.setState("active"); assertTrue(domain.complete(start, pool, Map.of(), before));
        pool.setUuid("replacement"); assertFalse(domain.complete(start, pool, Map.of(), before)); pool.setUuid("registry-uuid");
        pool.setData(Map.of("fields", Map.of("serverAddress", "other.example")));
        assertFalse(domain.complete(start, pool, Map.of(), before)); pool.setData((Map<String, Object>) before.get("data"));
        pool.setKind("docker"); assertFalse(domain.complete(start, pool, Map.of(), before)); pool.setKind("registry");
        assertThrows(ClientVisibleException.class, () -> domain.ready(start, pool, null));
        var stop = AiResourceChangeDescriptor.require("registry", "stop"); var active = domain.snapshot(pool);
        domain.ready(stop, pool, null); pool.setState("inactive"); assertTrue(domain.complete(stop, pool, Map.of(), active));
        pool.setData(Map.of("fields", Map.of("serverAddress", "user:PRIVATE@registry.example")));
        assertThrows(ClientVisibleException.class, () -> domain.ready(start, pool, null));
    }

    static StoragePoolRecord registry() {
        var pool = new StoragePoolRecord(); pool.setId(3L); pool.setUuid("registry-uuid"); pool.setAccountId(5L);
        pool.setKind("registry"); pool.setState("inactive");
        pool.setData(Map.of("fields", Map.of("serverAddress", "registry.example:5000"))); return pool;
    }

    @Test public void registryConfirmationAndApplyCasRejectChangedUuidStateAndConfigurationBeforeDispatch() {
        for (String field : List.of("uuid", "state", "data", "storageDriverId")) {
            var f = new AiResourceChangePlanServiceTest.Fixture();
            var state = new LinkedHashMap<String, Object>(Map.of("id", 7L, "uuid", "registry", "accountId", 77L,
                    "kind", "registry", "state", "inactive", "data", Map.of("address", "registry.example"), "storageDriverId", 2L));
            when(f.domain.snapshot(any())).thenAnswer(call -> call.getArgument(0) == null ? Map.of() : f.copy(state));
            preview(f, true); Object previous = state.put(field, "replacement");
            assertThrows(ClientVisibleException.class, f::confirm); assertEquals(0, f.claims);
            state.put(field, previous); f.confirm(); state.put(field, "replacement");
            assertThrows(ClientVisibleException.class, () -> f.apply(true)); assertEquals(0, f.claims); assertEquals(0, f.dispatched);
        }
    }

    @Test public void registryConfirmationRemainsHumanOnlyBoundKeyIsFreshAndAcceptedOrUnknownApplyNeverReplays() {
        for (boolean unknown : List.of(false, true)) {
            var f = new AiResourceChangePlanServiceTest.Fixture(); preview(f, true);
            assertThrows(ClientVisibleException.class, () -> f.service.action(f.id, f.request("confirm", true)));
            f.confirm(); assertThrows(ClientVisibleException.class, () -> f.apply(false));
            var changed = f.request("apply", true);
            io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext.attach(changed,
                    new io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 4, null));
            assertThrows(ClientVisibleException.class, () -> f.service.action(f.id, changed)); assertEquals(0, f.dispatched);
            f.failDispatch = unknown; f.apply(true); f.apply(true); assertEquals(1, f.claims); assertEquals(1, f.dispatched);
        }
    }

    private void preview(AiResourceChangePlanServiceTest.Fixture f, boolean key) {
        var request = f.request(null, key); request.setId(null);
        request.setRequestObject(Map.of("resourceType", "registry", "operation", "start", "resourceId", "1sp7", "parameters", Map.of()));
        f.id = (String) f.service.preview(request).get("id");
    }

    @Test public void nativeGetAndTerminalReadReauthorizeTheExactRegistryIdUuidKindOwnerAndCurrentReadSchema() throws Exception {
        var owner = mock(io.cattle.platform.api.auth.Policy.class); var pool = registry();
        when(owner.getAuthenticatedAsAccountId()).thenReturn(10L); when(owner.getAccountId()).thenReturn(5L);
        when(owner.authorizeObject(pool)).thenReturn(pool);
        var domain = new AiResourceChangeDomain() {
            @Override public io.cattle.platform.api.auth.Policy actor(io.github.ibuildthecloud.gdapi.request.ApiRequest request) { return owner; }
            @Override public Object load(String type, long id) { return pool; }
        };
        domain.json = new io.cattle.platform.json.JacksonJsonMapper();
        domain.managers = mock(io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator.class);
        var manager = mock(io.github.ibuildthecloud.gdapi.request.resource.ResourceManager.class);
        when(domain.managers.getResourceManagerByType("registry")).thenReturn(manager);
        when(manager.getById(eq("registry"), anyString(), any())).thenReturn(pool);
        var schema = mock(io.github.ibuildthecloud.gdapi.model.Schema.class);
        var schemas = mock(io.github.ibuildthecloud.gdapi.factory.SchemaFactory.class);
        when(schemas.getSchema("registry")).thenReturn(schema); when(schemas.getBaseType("registry")).thenReturn("storagePool");
        when(schema.getResourceMethods()).thenReturn(List.of("GET")); when(schema.getResourceActions()).thenReturn(Map.of());
        var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
        formatter.setTypeMappings(Map.of("storagePool", "sp"));
        domain.targets = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver();
        var member = domain.targets.getClass().getDeclaredField("idFormatter"); member.setAccessible(true); member.set(domain.targets, formatter);
        domain.authenticator = mock(io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.class);
        when(domain.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(
                new io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.CurrentAuthorization(owner, schemas));
        domain.validation = mock(io.github.ibuildthecloud.gdapi.validation.ValidationHandler.class);
        domain.keyAccess = mock(io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService.class);
        var request = new io.github.ibuildthecloud.gdapi.request.ApiRequest(null, schemas);
        io.github.ibuildthecloud.gdapi.context.ApiContext.newContext().setPolicy(owner);
        var descriptor = AiResourceChangeDescriptor.require("registry", "start");
        assertSame(pool, domain.visible(request, descriptor, "1sp3"));
        assertThrows(ClientVisibleException.class, () -> domain.prepare(request, descriptor, "1sp3", Map.of()));
        pool.setId(4L); assertThrows(ClientVisibleException.class, () -> domain.visible(request, descriptor, "1sp3")); pool.setId(3L);
        var plan = new LinkedHashMap<String, Object>(Map.of("resourceType", "registry", "operation", "start", "resourceId", "1sp3",
                "internalResourceId", 3L, "status", "succeeded", "snapshot", domain.snapshot(pool)));
        var erase = AiResourceChangePlanService.class.getDeclaredMethod("eraseSnapshot", Map.class); erase.setAccessible(true);
        erase.invoke(new AiResourceChangePlanService(), plan);
        assertFalse(plan.containsKey("snapshot")); assertTrue(plan.containsKey("registryIdentity"));
        assertSame(pool, domain.receiptTarget(request, descriptor, plan));
        for (String mutation : List.of("uuid", "id", "kind", "owner", "schema")) {
            switch (mutation) {
            case "uuid" -> pool.setUuid("replacement");
            case "id" -> pool.setId(4L);
            case "kind" -> pool.setKind("local");
            case "owner" -> when(owner.authorizeObject(pool)).thenReturn(null);
            case "schema" -> when(schema.getResourceMethods()).thenReturn(List.of());
            default -> { }
            }
            assertThrows(mutation, ClientVisibleException.class, () -> domain.receiptTarget(request, descriptor, plan));
            pool.setUuid("registry-uuid"); pool.setId(3L); pool.setKind("registry");
            when(owner.authorizeObject(pool)).thenReturn(pool); when(schema.getResourceMethods()).thenReturn(List.of("GET"));
        }
        assertFalse(new AiResourceChangePlanService().output(plan).containsKey("registryIdentity"));
        assertFalse(plan.containsKey("cookieLifecycleIdentity")); assertNull(domain.processObserver);
        domain.objects = mock(io.cattle.platform.object.ObjectManager.class);
        var project = new io.cattle.platform.core.model.tables.records.AccountRecord(); project.setId(5L); project.setKind("project");
        project.setUuid("project-uuid"); when(domain.objects.loadResource(io.cattle.platform.core.model.Account.class, 5L)).thenReturn(project);
        domain.processObserver = new AiResourceChangeProcessObserver(); domain.processObserver.objects = domain.objects;
        domain.processObserver.json = domain.json; domain.processObserver.store = mock(AiResourceChangePlanStore.class);
        var service = new AiResourceChangePlanService(); service.domain = domain;
        service.store = mock(AiResourceChangePlanStore.class); service.clock = java.time.Clock.systemUTC();
        var snapshot = AiResourceChangePlanService.class.getDeclaredMethod("targetSnapshot",
                io.github.ibuildthecloud.gdapi.request.ApiRequest.class, AiResourceChangeDescriptor.class, Object.class, Map.class);
        snapshot.setAccessible(true);
        var cookieBefore = (Map<String, Object>) snapshot.invoke(service, request, descriptor, pool, Map.of());
        assertTrue(cookieBefore.containsKey(AiCookieLifecycleWitness.SNAPSHOT));
        var cookie = new LinkedHashMap<String, Object>(Map.of("resourceType", "registry", "operation", "start", "resourceId", "1sp3",
                "internalResourceId", 3L, "status", "accepted", "snapshot", cookieBefore, "verifiedResult", false));
        assertSame(pool, domain.receiptTarget(request, descriptor, cookie));
        for (Object broken : java.util.Arrays.asList(null, domain.json.readValue("{\"root\":null}"), Map.of(),
                Map.of("root", "invalid"), Map.of("root", Map.of()), "invalid")) {
            var invalid = new LinkedHashMap<>(cookie); var brokenSnapshot = new LinkedHashMap<>(cookieBefore);
            brokenSnapshot.put(AiCookieLifecycleWitness.SNAPSHOT, broken); invalid.put("snapshot", brokenSnapshot);
            var rejected = assertThrows(ClientVisibleException.class, () -> domain.receiptTarget(request, descriptor, invalid));
            assertEquals(409, rejected.getStatus()); assertEquals("ResourceChangePlanStale", rejected.getCode());
            assertEquals(false, invalid.get("verifiedResult"));
        }
        erase.invoke(service, cookie); assertSame(pool, domain.receiptTarget(request, descriptor, cookie));
        var replaced = new LinkedHashMap<>((Map<String, Object>) cookie.get("cookieLifecycleIdentity")); replaced.put("uuid", "replaced");
        for (Object broken : java.util.Arrays.asList(null, "invalid", Map.of(), replaced)) {
            var invalid = new LinkedHashMap<>(cookie); invalid.put("cookieLifecycleIdentity", broken);
            assertEquals(409, assertThrows(ClientVisibleException.class,
                    () -> domain.receiptTarget(request, descriptor, invalid)).getStatus());
        }
        cookie.remove("cookieLifecycleIdentity");
        assertSame(pool, domain.receiptTarget(request, descriptor, cookie)); // A native read is not new completion proof.
        assertEquals("unknown", domain.cookieLifecycleOutcome(cookie, pool));
        var missing = new LinkedHashMap<>(cookie); missing.put("snapshot", domain.snapshot(pool));
        missing.put("dispatchAcknowledged", true); missing.put("id", "own-cookie-plan"); missing.put("parameters", Map.of());
        pool.setState("active"); assertTrue(domain.complete(descriptor, pool, Map.of(), (Map<String, Object>) missing.get("snapshot")));
        service.observe(missing, request); assertEquals("unknown", missing.get("status")); assertEquals(false, missing.get("verifiedResult"));
        io.github.ibuildthecloud.gdapi.context.ApiContext.remove();
    }
}
