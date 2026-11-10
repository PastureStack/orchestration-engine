package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.StoragePoolRecord;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** Direct authorization and its audit decision must agree with the row predicate. */
public class ApiKeyRegistryTargetScopeTest {
    private ApiKeyTargetResolver resolver() {
        var resolver = new ApiKeyTargetResolver();
        resolver.objectManager = mock(ObjectManager.class);
        resolver.idFormatter = mock(IdFormatter.class);
        when(resolver.idFormatter.formatId(anyString(), any())).thenAnswer(call ->
                ("project".equals(call.getArgument(0)) ? "1a" : "1sp") + call.getArgument(1));
        var project = new AccountRecord(); project.setId(5L); project.setKind("project");
        when(resolver.objectManager.loadResource(Account.class, 5L)).thenReturn(project);
        return resolver;
    }

    private StoragePoolRecord pool(String kind) {
        var pool = new StoragePoolRecord(); pool.setId(71L); pool.setAccountId(5L); pool.setKind(kind);
        return pool;
    }

    private Policy owner() {
        var owner = mock(Policy.class); when(owner.authorizeObject(any())).thenAnswer(call -> call.getArgument(0));
        return owner;
    }

    private ApiKeyPolicyEvaluator.Decision decision(ApiKeyPolicy.Effect base, ApiKeyPolicy.Effect rule,
            ApiKeyPolicyEvaluator.Target target) {
        var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, base, null,
                List.of(new ApiKeyPolicy.Rule("registry", rule, ApiKeyPolicy.Scope.resource("registry", "1sp71"), Set.of("read"))));
        return new ApiKeyPolicyEvaluator().evaluate(policy,
                new ApiKeyPolicyEvaluator.Request(true, "read", true, List.of(target)), Instant.EPOCH);
    }

    @Test public void exactRegistryGrantUsesPersistedKindThroughBothNativeSchemaNames() {
        var resolver = resolver(); var row = pool("registry"); var owner = owner();
        for (String type : List.of("registry", "storagePool")) {
            var target = resolver.resolveObject(type, row, owner);
            assertEquals("registry", target.resourceType()); assertEquals("1sp71", target.resourceId());
            assertTrue(decision(ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Effect.ALLOW, target).allowed());
        }
    }

    @Test public void parentAliasCannotHideRegistryPolicyDeniedFromAuthorizationOrAudit() {
        var resolver = resolver(); var row = pool("registry"); var owner = owner();
        for (String type : List.of("registry", "storagePool")) {
            var target = resolver.resolveObject(type, row, owner);
            var denied = decision(ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Effect.DENY, target);
            assertFalse(denied.allowed()); assertEquals(ApiKeyPolicyEvaluator.Reason.POLICY_DENIED, denied.reason());
            assertEquals(List.of("registry"), denied.matchedRuleIds());
        }
    }

    @Test public void anOrdinaryPoolAndOwnerDenialCannotBorrowRegistryAuthority() {
        var resolver = resolver(); var row = pool("docker"); var owner = owner();
        var target = resolver.resolveObject("storagePool", row, owner);
        assertEquals("storagePool", target.resourceType());
        assertFalse(decision(ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Effect.ALLOW, target).allowed());
        when(owner.authorizeObject(row)).thenReturn(null);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.resolveObject("storagePool", row, owner)).getCode());
    }
}
