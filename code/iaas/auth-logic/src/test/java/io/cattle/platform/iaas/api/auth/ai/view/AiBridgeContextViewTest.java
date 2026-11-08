package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AiBridgeContextViewTest {
    private Object enabled;
    @Before public void enable() {
        enabled = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
    }
    @After public void cleanup() {
        if (enabled == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", enabled);
        ApiContext.remove();
    }

    @Test public void contextSelectorPurposeAndRevisionAreClosedBeforeAnyNativeRead() {
        for (Map<String, Object> body : List.of(Map.<String, Object>of("resourceType", "stack", "view", "bridge_context", "query", "Visible"),
                Map.<String, Object>of("resourceType", "project", "view", "bridge_context"),
                Map.<String, Object>of("resourceType", "project", "view", "bridge_context", "query", ""),
                Map.<String, Object>of("resourceType", "project", "view", "bridge_context", "resourceId", "77", "query", "Visible"))) {
            Fixture f = new Fixture(); assertEquals("ResourceViewInputInvalid", rejected(() -> f.service.read(f.request(body))));
            verifyNoInteractions(f.manager, f.service.objects, f.service.bridgeTargets);
        }
        for (String extra : List.of("marker", "parentId", "sourcePage", "tailLines", "expectedUUID", "unknown")) {
            Fixture f = new Fixture(); Map<String, Object> body = new LinkedHashMap<>(named()); body.put(extra, "x");
            assertEquals("ResourceViewInputInvalid", rejected(() -> f.service.read(f.request(body))));
            verifyNoInteractions(f.manager, f.service.objects, f.service.bridgeTargets);
        }
        for (int kind = 0; kind < 3; kind++) {
            Fixture f = new Fixture();
            if (kind == 0) f.policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of());
            if (kind == 1) f.policy = null;
            if (kind == 2) f.attachKey = false;
            assertEquals("DedicatedAiBridgeKeyRequired", rejected(() -> f.service.read(f.request(named()))));
            verifyNoInteractions(f.manager, f.service.objects, f.service.bridgeTargets);
        }
        Fixture stale = new Fixture(); stale.revision = null;
        assertEquals("ApiKeyPolicyChanged", rejected(() -> stale.service.read(stale.request(named()))));
        verifyNoInteractions(stale.manager, stale.service.objects, stale.service.bridgeTargets);
    }

    @Test public void scopedStackReadProvesOnlyItsFreshFourFieldParentAndNeverLendsAProjectGrant() {
        Fixture f = new Fixture(); ApiRequest request = f.request(named());
        Map<String, Object> result = f.service.read(request);
        assertEquals(List.of(Map.of("id", "77", "uuid", "adminProject", "name", "Visible", "state", "active")), result.get("data"));
        assertNull(result.get("nextMarker")); assertEquals(Set.of("id", "resourceType", "data", "nextMarker"), result.keySet());
        assertFalse(result.toString().contains("PRIVATE"));
        assertEquals(result.get("data"), f.service.read(f.request(bound())).get("data"));
        assertEquals(List.of(), f.service.read(f.request(Map.of("resourceType", "project", "view", "bridge_context", "query", "Vis"))).get("data"));
        assertEquals(List.of(), f.service.read(f.request(Map.of("resourceType", "project", "view", "bridge_context", "query", "visible"))).get("data"));
        assertEquals("KeyScopeDenied", rejected(() -> f.service.read(f.request(Map.of("resourceType", "project", "resourceId", "77")))));
        verify(f.manager, atLeast(2)).getById(eq("stack"), eq("99"), any());
        verify(f.service.bridgeTargets, atLeast(2)).resolveObject(eq("stack"), any(), same(f.live));
        assertNull(request.getAttribute(AiResourceViewService.DOMAIN_READ_ATTRIBUTE));
        assertSame(f.originalContext, ApiContext.getContext().getApiRequest()); assertSame(f.owner, ApiContext.getContext().getPolicy());
    }

    @Test public void explicitParentDenialsAndMissingOrdinaryChildAuthorityNeverYieldContextIdentity() {
        for (ApiKeyPolicy.Scope scope : List.of(ApiKeyPolicy.Scope.global(), ApiKeyPolicy.Scope.project("77"),
                ApiKeyPolicy.Scope.resource("project", "77"))) {
            Fixture f = new Fixture();
            f.policy = policy(List.of(allow(), new ApiKeyPolicy.Rule("deny-parent", ApiKeyPolicy.Effect.DENY, scope, Set.of("read"))));
            assertEquals("KeyPolicyDenied", rejected(() -> f.service.read(f.request(named()))));
            verify(f.manager, never()).getById(eq("stack"), anyString(), any());
        }
        for (String code : List.of("KeyScopeDenied", "KeyPolicyDenied", "OwnerPermissionDenied", "NotFound")) {
            Fixture f = new Fixture(); f.childDenied = code;
            assertEquals(List.of(), f.service.read(f.request(named())).get("data"));
            assertSame(f.originalContext, ApiContext.getContext().getApiRequest());
        }
        Fixture hidden = new Fixture(); hidden.stack.setRemoved(new java.util.Date());
        assertEquals(List.of(), hidden.service.read(hidden.request(named())).get("data"));
        Fixture foreign = new Fixture();
        when(foreign.service.bridgeTargets.resolveObject(eq("stack"), any(), any())).thenReturn(
                ApiKeyPolicyEvaluator.Target.stackResource("stack", "99", "88", "99"));
        assertEquals(List.of(), foreign.service.read(foreign.request(bound())).get("data"));
    }

    @Test public void keyRoleAndNativeIdentityChangesBeforeCompletionFailClosedAndRestoreTheOriginalContext() {
        for (String code : List.of("ApiKeyExpired", "ApiKeyRevoked", "ApiKeyPolicyChanged")) {
            Fixture f = new Fixture();
            when(f.manager.getById(eq("stack"), eq("99"), any())).thenAnswer(call -> {
                when(f.service.keyAuthorization.actor(any())).thenThrow(new ClientVisibleException(403, code)); return f.stack;
            });
            assertEquals(code, rejected(() -> f.service.read(f.request(named()))));
            assertSame(f.originalContext, ApiContext.getContext().getApiRequest()); assertSame(f.owner, ApiContext.getContext().getPolicy());
        }
        Fixture uuid = new Fixture();
        when(uuid.manager.getById(eq("stack"), eq("99"), any())).thenAnswer(call -> { uuid.stack.setUuid("replaced-child"); return uuid.stack; });
        assertEquals("ResourceViewUnavailable", rejected(() -> uuid.service.read(uuid.request(named()))));
        Fixture parent = new Fixture();
        when(parent.manager.getById(eq("stack"), eq("99"), any())).thenAnswer(call -> { parent.project.setUuid("replaced-parent"); return parent.stack; });
        assertEquals("ResourceViewUnavailable", rejected(() -> parent.service.read(parent.request(named()))));
        Fixture role = new Fixture(); when(role.live.authorizeObject(role.project)).thenReturn(null);
        assertEquals("OwnerPermissionDenied", rejected(() -> role.service.read(role.request(named()))));
        Fixture field = new Fixture(); when(field.schema.getResourceFields()).thenReturn(Map.of());
        assertEquals(List.of(), field.service.read(field.request(named())).get("data"));
    }

    @Test public void fullAiPurposeUsesItsOrdinaryProjectOwnerAndIgnoresStaleCustomRuleDenials() {
        Fixture f = new Fixture(); f.parentReadable = true;
        f.policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null,
                List.of(new ApiKeyPolicy.Rule("stale-deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.global(), Set.of("read"))),
                ApiKeyPolicy.Purpose.AI_BRIDGE);
        assertEquals(List.of(Map.of("id", "77", "uuid", "adminProject", "name", "Visible", "state", "active")),
                f.service.read(f.request(named())).get("data"));
        verify(f.manager, atLeastOnce()).getById(eq("project"), eq("77"), any());
        verify(f.manager, never()).getById(eq("stack"), anyString(), any());
    }

    @Test public void generalLegacyAndCookieProjectReadsKeepTheirOriginalDomainPathAndProjection() {
        for (int kind = 0; kind < 3; kind++) {
            Fixture f = new Fixture(); f.parentReadable = true;
            if (kind == 0) f.policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of());
            if (kind == 1) f.policy = null;
            if (kind == 2) f.attachKey = false;
            Map<String, Object> result = f.service.read(f.request(Map.of("resourceType", "project", "resourceId", "77")));
            Map<?, ?> row = (Map<?, ?>) ((List<?>) result.get("data")).getFirst();
            assertEquals("Visible", row.get("name")); assertFalse(row.containsKey("uuid"));
            verifyNoInteractions(f.service.bridgeTargets);
        }
    }

    private static Map<String, Object> named() { return Map.of("resourceType", "project", "view", "bridge_context", "query", "Visible"); }
    private static Map<String, Object> bound() { return Map.of("resourceType", "project", "view", "bridge_context", "resourceId", "77"); }
    private static ApiKeyPolicy.Rule allow() {
        return new ApiKeyPolicy.Rule("own-stack", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("99"), Set.of("read", "upgrade"));
    }
    private static ApiKeyPolicy policy(List<ApiKeyPolicy.Rule> rules) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, rules, ApiKeyPolicy.Purpose.AI_BRIDGE);
    }
    private static String rejected(Runnable operation) {
        try { operation.run(); fail("must reject"); return null; }
        catch (ClientVisibleException denied) { return denied.getCode(); }
    }

    static final class Fixture extends AiResourceViewServiceTest.Fixture {
        final AccountRecord project = new AccountRecord();
        final StackRecord stack = new StackRecord();
        ApiKeyPolicy policy = policy(List.of(allow()));
        boolean parentReadable, attachKey = true;
        String childDenied, revision = "3";
        Fixture() {
            project.setId(77L); project.setKind("project"); project.setUuid("adminProject"); project.setName("Visible"); project.setState("active");
            stack.setId(99L); stack.setAccountId(77L); stack.setUuid("stack-uuid"); stack.setName("child"); stack.setState("active");
            service.objects = mock(ObjectManager.class); service.bridgeTargets = mock(ApiKeyTargetResolver.class);
            when(service.objects.loadResource(eq("stack"), eq(99L))).thenReturn(stack);
            when(service.objects.loadResource(eq("project"), eq(77L))).thenReturn(project);
            when(service.objects.loadResource(Account.class, 77L)).thenReturn(project);
            when(service.bridgeTargets.parseScopeId(anyString(), anyString())).thenAnswer(call -> Long.parseLong(call.getArgument(1)));
            when(service.bridgeTargets.resolveObject(eq("stack"), any(), any()))
                    .thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("stack", "99", "77", "99"));
            when(service.bridgeTargets.resolveObject(eq("project"), any(), any()))
                    .thenReturn(ApiKeyPolicyEvaluator.Target.projectResource("project", "77", "77"));
            when(live.authorizeObject(any())).thenAnswer(call -> call.getArgument(0));
            Map<String, Field> fields = new LinkedHashMap<>();
            for (String name : List.of("id", "uuid", "accountId", "name", "state")) {
                Field field = mock(Field.class); when(field.isIncludeInList()).thenReturn(true); fields.put(name, field);
            }
            when(schema.getResourceFields()).thenReturn(fields);
            doAnswer(call -> {
                ApiRequest domain = call.getArgument(0);
                if ("project".equals(domain.getType()) && domain.getId() != null && !parentReadable)
                    throw new ClientVisibleException(403, "KeyScopeDenied");
                if ("stack".equals(domain.getType()) && childDenied != null) throw new ClientVisibleException(403, childDenied);
                return null;
            }).when(keyAccess).authorize(any(), any());
            when(manager.list(eq("project"), any(ApiRequest.class))).thenAnswer(call -> parentReadable ? List.of(project) : List.of());
            when(manager.getById(eq("stack"), eq("99"), any())).thenReturn(stack);
            when(manager.getById(eq("project"), eq("77"), any())).thenReturn(project);
            when(manager.convertResponse(any(Object.class), any())).thenAnswer(call -> {
                Object row = call.getArgument(0); ApiRequest domain = call.getArgument(1);
                return new ResourceImpl("project".equals(domain.getType()) ? "77" : "99", domain.getType(),
                        Map.of("name", row == project ? project.getName() : stack.getName(), "state", "active", "uuid",
                                row == project ? project.getUuid() : stack.getUuid(), "accountId", "77", "password", "PRIVATE"));
            });
        }
        @Override ApiRequest request(Map<String, Object> body) {
            ApiRequest request = super.request(body); request.setType("aiResourceView");
            HttpServletRequest http = mock(HttpServletRequest.class);
            when(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenAnswer(call -> revision);
            request.setRequestServletContext(new ApiServletContext(http, null, null));
            if (attachKey) ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKeyRestricted", 3, policy));
            return request;
        }
    }
}
