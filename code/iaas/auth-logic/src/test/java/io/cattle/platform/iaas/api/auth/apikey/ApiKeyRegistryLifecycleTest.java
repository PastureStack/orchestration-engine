package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.core.model.tables.records.StoragePoolRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.TypeIdFormatter;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class ApiKeyRegistryLifecycleTest {
    @Test public void nativeStoragePoolRootUsesTheExactRegistryRoleAndRealCanonicalIdParser() {
        for (String action : List.of("activate", "deactivate")) for (String mode : List.of("custom", "full", "legacy")) {
            var f = new Fixture(action); f.mode(mode); f.hook.beforeExecution(f.job, f.metadata);
            assertEquals(3L, f.targets.parseScopeId("registry", "1sp3").longValue());
            for (String invalid : List.of("3", "1s3", "-1"))
                assertThrows(RuntimeException.class, () -> f.targets.parseScopeId("registry", invalid));
            verify(f.objects, never()).persist(any());
        }
    }

    @Test public void aRegistryGrantCannotExecuteAnotherPoolIdKindOrUuidEvenForFullAndLegacyKeys() {
        for (String mode : List.of("custom", "full", "legacy")) for (String mutation : List.of("id", "zero", "kind", "uuid", "process", "type")) {
            var f = new Fixture("activate"); f.mode(mode);
            var child = pool(3L); String id = "3", type = "storagePool", process = "storagepool.activate";
            switch (mutation) {
            case "id" -> { child.setId(4L); id = "4"; }
            case "zero" -> { child.setId(0L); id = "0"; }
            case "kind" -> child.setKind("local");
            case "uuid" -> child.setUuid("replacement");
            case "process" -> process = "storagepool.remove";
            case "type" -> type = "registryCredential";
            default -> { }
            }
            when(f.objects.loadResource(type, id)).thenReturn(child);
            var wrong = new LaunchConfiguration(process, type, id, 5L, 0, Map.of());
            assertThrows(mode + "/" + mutation, ProcessAuthorizationDeniedException.class,
                    () -> f.hook.beforeExecution(wrong, f.metadata));
        }
    }

    @Test public void liveReadonlyRoleReadCeilingKeyRevisionRevocationExpiryAndExplicitDeniesRemainBinding() {
        for (String mutation : List.of("role", "get", "readonly", "revision", "revoked", "expired", "deny", "pool-deny", "owner")) {
            var f = new Fixture("activate");
            switch (mutation) {
            case "role" -> when(f.schema.getResourceActions()).thenReturn(Map.of());
            case "get" -> when(f.schema.getResourceMethods()).thenReturn(List.of());
            case "readonly" -> f.policy(Set.of("read"), false, false, null, 1);
            case "revision" -> f.policy(Set.of("read", "update"), false, false, null, 2);
            case "revoked" -> f.key.setState("inactive");
            case "expired" -> f.policy(Set.of("read", "update"), false, false, Instant.parse("2026-01-01T00:00:00Z"), 1);
            case "deny" -> f.policy(Set.of("read", "update"), true, false, null, 1);
            case "pool-deny" -> f.policy(Set.of("read", "update"), false, true, null, 1);
            case "owner" -> when(f.owner.authorizeObject(f.root)).thenReturn(null);
            default -> { }
            }
            assertThrows(mutation, ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(f.job, f.metadata));
        }
    }

    static StoragePoolRecord pool(long id) {
        var pool = new StoragePoolRecord(); pool.setId(id); pool.setAccountId(5L); pool.setUuid("registry-" + id);
        pool.setKind("registry"); pool.setState("inactive"); pool.setData(Map.of("fields", Map.of("serverAddress", "registry.example"))); return pool;
    }
    static class Fixture {
        final ApiKeyProcessAuthorization hook = new ApiKeyProcessAuthorization();
        final ObjectManager objects = mock(ObjectManager.class); final Policy owner = mock(Policy.class);
        final Schema schema = mock(Schema.class); final CredentialRecord key = new CredentialRecord();
        final StoragePoolRecord root = pool(3L); final ApiKeyTargetResolver targets = new ApiKeyTargetResolver();
        final Map<String, Object> metadata = new LinkedHashMap<>(); final LaunchConfiguration job;
        Fixture(String action) {
            var formatter = new TypeIdFormatter() { };
            formatter.setTypeMappings(Map.of("registry", "sp", "storagePool", "sp", "credential", "c", "project", "a"));
            targets.objectManager = objects; targets.idFormatter = formatter;
            hook.objectManager = objects; hook.targets = targets; hook.authenticator = mock(ApiAuthenticator.class);
            hook.clock = Clock.fixed(Instant.parse("2026-11-07T00:00:00Z"), ZoneOffset.UTC);
            var schemas = mock(SchemaFactory.class); when(schemas.getSchema("registry")).thenReturn(schema);
            formatter.setSchemaFactory(schemas);
            when(schemas.getBaseType(anyString())).thenAnswer(call -> "registry".equals(call.getArgument(0)) ? "storagePool" : call.getArgument(0));
            when(schema.getResourceMethods()).thenReturn(List.of("GET"));
            when(schema.getResourceActions()).thenReturn(Map.of("activate", mock(Action.class), "deactivate", mock(Action.class)));
            when(owner.getAccountId()).thenReturn(5L); when(owner.getAuthenticatedAsAccountId()).thenReturn(10L);
            when(owner.authorizeObject(any())).thenAnswer(call -> call.getArgument(0));
            when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, schemas));
            key.setId(1L); key.setAccountId(10L); key.setKind("apiKeyRestricted"); key.setState("active");
            when(objects.loadResource(Credential.class, 1L)).thenReturn(key);
            var project = new io.cattle.platform.core.model.tables.records.AccountRecord(); project.setId(5L); project.setKind("project");
            when(objects.loadResource(io.cattle.platform.core.model.Account.class, 5L)).thenReturn(project);
            when(objects.loadResource("registry", 3L)).thenReturn(root); when(objects.loadResource("storagePool", "3")).thenReturn(root);
            metadata.putAll(Map.of("keyId", "1c1", "policyRevision", 1L, "principalAccountId", 10L, "accountId", 5L,
                    "operation", "update", "targetType", "registry", "targetId", "3", "requestMethod", "POST", "requestCollection", false));
            metadata.put("requestAction", action); metadata.put("preview", false);
            policy(Set.of("read", "update"), false, false, null, 1);
            job = new LaunchConfiguration("storagepool." + action, "storagePool", "3", 5L, 0, Map.of());
        }
        void mode(String mode) {
            if (mode.equals("legacy")) { key.setKind("apiKey"); key.setData(null); metadata.put("policyRevision", 0L); }
            if (mode.equals("full")) key.setData(new ApiKeyPolicyCodec().store(key,
                    new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()), 1));
        }
        void policy(Set<String> operations, boolean deny, boolean poolDeny, Instant expiry, long revision) {
            var rules = new java.util.ArrayList<ApiKeyPolicy.Rule>();
            rules.add(new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("registry", "1sp3"), operations));
            if (deny || poolDeny) rules.add(new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY,
                    ApiKeyPolicy.Scope.resource(poolDeny ? "storagePool" : "registry", "1sp3"), Set.of("update")));
            key.setData(new ApiKeyPolicyCodec().store(key, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, expiry, rules), revision));
        }
    }
}
