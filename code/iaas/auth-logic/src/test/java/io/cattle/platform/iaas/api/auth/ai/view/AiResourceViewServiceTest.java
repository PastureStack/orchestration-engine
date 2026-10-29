package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiResourceViewServiceTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void sensitiveProjectionNeverCopiesValuesCredentialsOrNestedPayloads() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", "safe-name"); fields.put("state", "active");
        for (String key : List.of("key", "cert", "secretValue", "publicValue", "value", "activeValue", "password", "token", "data", "environment"))
            fields.put(key, "DO-NOT-LEAK-" + key);
        fields.put("launchConfig", Map.of("imageUuid", "docker:busybox:1.37.0", "environment", Map.of("TOKEN", "DO-NOT-LEAK")));
        fields.put("apiKeyPolicy", Map.of("mode", "full", "purpose", "ai-bridge", "expiresAt", "2030-01-01T00:00:00Z",
                "rules", List.of(Map.of("secret", "DO-NOT-LEAK"))));
        for (String type : AiResourceViewService.READ_TYPES) {
            Map<String, Object> result = AiResourceViewService.project(type, "1", fields);
            assertEquals("safe-name", result.get("name"));
            assertFalse(type, result.toString().contains("DO-NOT-LEAK"));
            assertFalse(result.containsKey("launchConfig")); assertFalse(result.containsKey("apiKeyPolicy"));
        }
        assertEquals("docker:busybox:1.37.0", AiResourceViewService.project("service", "1", fields).get("imageUuid"));
        assertEquals("ai-bridge", AiResourceViewService.project("apiKey", "1", fields).get("purpose"));
    }

    @Test public void rejectsUnregisteredTypesUrlsMethodsAndCallerIdentityBeforeDispatch() {
        Fixture f = new Fixture();
        for (Map<String, Object> body : List.of(Map.<String, Object>of("resourceType", "password"),
                Map.<String, Object>of("resourceType", "certificate", "url", "https://other/"),
                Map.<String, Object>of("resourceType", "stack", "resourceId", "../../tokens"),
                Map.<String, Object>of("resourceType", "stack", "actorId", 1),
                Map.<String, Object>of("resourceType", "stack", "method", "DELETE"),
                Map.<String, Object>of("resourceType", "stack", "query", "x".repeat(257)))) {
            assertEquals("ResourceViewInputInvalid", rejected(() -> f.service.read(f.request(body))));
        }
        verifyNoInteractions(f.manager, f.keyAccess, f.authenticator);
    }

    @Test public void globalReadsRebuildTheVerifiedPrincipalAuthorityAndRestoreContext() {
        Fixture f = new Fixture(); f.rows.add(row("1", "safe"));
        ApiRequest original = f.request(Map.of("resourceType", "certificate"));
        f.service.read(original);
        verify(f.authenticator).currentAuthorization(eq(42L), eq(77L), any());
        f.service.read(f.request(Map.of("resourceType", "setting")));
        verify(f.authenticator).currentAuthorization(eq(42L), eq(42L), any());
        assertSame(f.originalContext, ApiContext.getContext().getApiRequest());
        assertSame(f.owner, ApiContext.getContext().getPolicy());
        assertNull(original.getAttribute(AiResourceViewService.DOMAIN_READ_ATTRIBUTE));
    }

    @Test public void ordinaryRbacAndDomainKeyDecisionHappenBeforeAnyResourceRead() {
        Fixture f = new Fixture();
        when(f.schema.getCollectionMethods()).thenReturn(List.of());
        assertEquals("OwnerPermissionDenied", rejected(() -> f.service.read(f.request(Map.of("resourceType", "certificate")))));
        verifyNoInteractions(f.manager);
        when(f.schema.getCollectionMethods()).thenReturn(List.of("GET"));
        doThrow(new ClientVisibleException(403, "KeyScopeDenied")).when(f.keyAccess).authorize(any(), any());
        assertEquals("KeyScopeDenied", rejected(() -> f.service.read(f.request(Map.of("resourceType", "certificate")))));
        verifyNoInteractions(f.manager);
        assertSame(f.originalContext, ApiContext.getContext().getApiRequest());
        assertSame(f.owner, ApiContext.getContext().getPolicy());
    }

    @Test public void scopedManagerRowsAndPaginationRemainBoundedWithoutSecrets() {
        Fixture f = new Fixture();
        for (int i = 1; i <= 51; i++) f.rows.add(row(Integer.toString(i), "stack-" + i));
        Map<String, Object> first = f.service.read(f.request(Map.of("resourceType", "stack")));
        assertEquals(50, ((List<?>) first.get("data")).size()); assertEquals("50", first.get("nextMarker"));
        Map<String, Object> later = f.service.read(f.request(Map.of("resourceType", "project", "marker", "50")));
        assertEquals(1, ((List<?>) later.get("data")).size()); assertNull(later.get("nextMarker"));
        assertFalse(first.toString().contains("private-payload"));
        verify(f.keyAccess, times(2)).authorize(any(), any());
    }

    @Test public void detailReturnsExactlyTheAuthorizedTargetOrNotFound() {
        Fixture f = new Fixture();
        when(f.manager.getById(eq("certificate"), eq("12"), any())).thenReturn(row("12", "qa-cert"));
        Map<String, Object> result = f.service.read(f.request(Map.of("resourceType", "certificate", "resourceId", "12")));
        assertEquals(1, ((List<?>) result.get("data")).size());
        assertEquals("NotFound", rejected(() -> f.service.read(f.request(Map.of("resourceType", "certificate", "resourceId", "13")))));
        verify(f.manager, never()).list(anyString(), any(ApiRequest.class));
    }

    @Test public void scopedKeyContextIsAttachedOnlyToTheInternalRead() {
        Fixture f = new Fixture(); f.rows.add(row("1", "safe"));
        ApiRequest original = f.request(Map.of("resourceType", "stack"));
        ApiKeyCredentialContext key = new ApiKeyCredentialContext(5, 42, "apiKeyRestricted", 3, null);
        ApiKeyCredentialContext.attach(original, key);
        doAnswer(call -> {
            ApiRequest domain = call.getArgument(0);
            assertEquals("stack", domain.getType()); assertEquals("GET", domain.getMethod());
            assertNull(domain.getAction()); assertNull(domain.getLink()); assertNull(domain.getRequestObject());
            assertEquals(Boolean.TRUE, domain.getAttribute(AiResourceViewService.DOMAIN_READ_ATTRIBUTE));
            assertSame(key, ApiKeyCredentialContext.get(domain));
            assertSame(domain, ApiContext.getContext().getApiRequest());
            return null;
        }).when(f.keyAccess).authorize(any(), any());
        f.service.read(original);
        assertNull(original.getAttribute(AiResourceViewService.DOMAIN_READ_ATTRIBUTE));
    }

    @Test public void resolvesDuplicateServiceParentsInOneAuthorizedBatchWithoutLeakingDeniedNames() {
        Fixture f = new Fixture();
        Resource child = new ResourceImpl("12", "service", new LinkedHashMap<>(Map.of("name", "same-name", "stackId", "99")));
        when(f.manager.list(anyString(), any(ApiRequest.class))).thenAnswer(call ->
                "stack".equals(call.getArgument(0)) ? List.of(row("99", "Parent A")) : List.of(child));
        Map<String, Object> result = f.service.read(f.request(Map.of("resourceType", "service")));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("data")).getFirst();
        assertEquals("Parent A", item.get("stackName")); assertEquals("resolved", item.get("parentResolution"));
        verify(f.manager, times(1)).list(eq("stack"), any(ApiRequest.class));
        doAnswer(call -> {
            if ("stack".equals(((ApiRequest) call.getArgument(0)).getType())) throw new ClientVisibleException(403, "KeyScopeDenied");
            return null;
        }).when(f.keyAccess).authorize(any(), any());
        result = f.service.read(f.request(Map.of("resourceType", "service")));
        item = (Map<?, ?>) ((List<?>) result.get("data")).getFirst();
        assertEquals("unavailable", item.get("parentResolution")); assertFalse(item.containsKey("stackName"));
        assertSame(f.originalContext, ApiContext.getContext().getApiRequest());
    }

    @Test public void actualRevisionGuardAcceptsVerifiedServletContextAcrossBothInternalHops() {
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            Fixture f = new Fixture();
            ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
            HttpServletRequest http = mock(HttpServletRequest.class);
            when(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
            ApiRequest request = f.request(Map.of("resourceType", "service"));
            request.setRequestServletContext(new ApiServletContext(http, null, null));
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKeyRestricted", 3, policy));
            Resource child = new ResourceImpl("12", "service", new LinkedHashMap<>(Map.of("name", "same-name", "stackId", "99")));
            when(f.manager.list(anyString(), any(ApiRequest.class))).thenAnswer(call ->
                    "stack".equals(call.getArgument(0)) ? List.of(row("99", "Parent A")) : List.of(child));
            doAnswer(call -> {
                ApiRequest domain = call.getArgument(0);
                assertSame(request.getServletContext(), domain.getServletContext());
                ApiKeyAiBridgeGuard.check(domain, policy);
                return null;
            }).when(f.keyAccess).authorize(any(), any());
            Map<String, Object> result = f.service.read(request);
            assertEquals("Parent A", ((Map<?, ?>) ((List<?>) result.get("data")).getFirst()).get("stackName"));
            verify(f.keyAccess, times(2)).authorize(any(), any());
            assertNull(request.getAttribute(AiResourceViewService.DOMAIN_READ_ATTRIBUTE));
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void actualRevisionGuardStillRejectsMissingOrWrongRevisionBeforeEitherDomainRead() {
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            Fixture f = new Fixture();
            ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
            HttpServletRequest http = mock(HttpServletRequest.class);
            ApiRequest request = f.request(Map.of("resourceType", "certificate"));
            request.setRequestServletContext(new ApiServletContext(http, null, null));
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKeyRestricted", 3, policy));
            doAnswer(call -> { ApiKeyAiBridgeGuard.check(call.getArgument(0), policy); return null; }).when(f.keyAccess).authorize(any(), any());
            for (String revision : java.util.Arrays.asList(null, "2")) {
                when(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn(revision);
                assertEquals("ApiKeyPolicyChanged", rejected(() -> f.service.read(request)));
            }
            verifyNoInteractions(f.manager);
            assertSame(f.originalContext, ApiContext.getContext().getApiRequest());
            Fixture later = new Fixture();
            HttpServletRequest rotating = mock(HttpServletRequest.class);
            when(rotating.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3", "2");
            ApiRequest withParent = later.request(Map.of("resourceType", "service"));
            withParent.setRequestServletContext(new ApiServletContext(rotating, null, null));
            ApiKeyCredentialContext.attach(withParent, new ApiKeyCredentialContext(5, 42, "apiKeyRestricted", 3, policy));
            when(later.manager.list(eq("service"), any(ApiRequest.class))).thenReturn(List.of(
                    new ResourceImpl("12", "service", new LinkedHashMap<>(Map.of("name", "child", "stackId", "99")))));
            doAnswer(call -> { ApiKeyAiBridgeGuard.check(call.getArgument(0), policy); return null; }).when(later.keyAccess).authorize(any(), any());
            assertEquals("ApiKeyPolicyChanged", rejected(() -> later.service.read(withParent)));
            verify(later.manager, never()).list(eq("stack"), any(ApiRequest.class));
            assertSame(later.originalContext, ApiContext.getContext().getApiRequest());
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void runtimePoolsWithoutDatabaseIdsUseOnlyTheirAuthorizedNamedCollection() {
        Fixture f = new Fixture();
        f.rows.add(new ResourceImpl(null, "processPool", new LinkedHashMap<>(Map.of("name", "api work / pool", "activeTasks", 3))));
        Map<String, Object> result = f.service.read(f.request(Map.of("resourceType", "processPool")));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) result.get("data")).getFirst();
        String id = (String) item.get("id"); assertTrue(id.matches("pool-[0-9a-f]{64}"));
        assertEquals(3, item.get("activeTasks"));
        result = f.service.read(f.request(Map.of("resourceType", "processPool", "resourceId", id)));
        assertEquals(id, ((Map<?, ?>) ((List<?>) result.get("data")).getFirst()).get("id"));
        verify(f.manager, never()).getById(anyString(), anyString(), any());
        assertEquals("NotFound", rejected(() -> f.service.read(f.request(Map.of("resourceType", "processPool", "resourceId", "pool-" + "0".repeat(64))))));
    }

    private static Resource row(String id, String name) {
        return new ResourceImpl(id, "stack", new LinkedHashMap<>(Map.of("name", name, "state", "active", "key", "private-payload")));
    }
    private static String rejected(Runnable operation) {
        try { operation.run(); fail("must reject"); return null; }
        catch (ClientVisibleException rejected) { return rejected.getCode(); }
    }
    static class Fixture {
        final AiResourceViewService service = new AiResourceViewService();
        final Policy owner = mock(Policy.class), live = mock(Policy.class);
        final Schema schema = mock(Schema.class); final SchemaFactory schemas = mock(SchemaFactory.class);
        final ApiAuthenticator authenticator = mock(ApiAuthenticator.class);
        final ApiKeyAuthorizationService keyAccess = mock(ApiKeyAuthorizationService.class);
        final ResourceManager manager = mock(ResourceManager.class);
        final List<Resource> rows = new ArrayList<>();
        final ApiRequest originalContext = new ApiRequest(null, null);
        Fixture() {
            when(owner.getAuthenticatedAsAccountId()).thenReturn(42L); when(owner.getAccountId()).thenReturn(77L);
            service.keyAuthorization = mock(AiKeyAuthorization.class); when(service.keyAuthorization.actor(any())).thenReturn(owner);
            when(authenticator.currentAuthorization(anyLong(), anyLong(), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(live, schemas));
            when(schemas.getSchema(anyString())).thenReturn(schema);
            when(schema.getCollectionMethods()).thenReturn(List.of("GET")); when(schema.getResourceMethods()).thenReturn(List.of("GET"));
            service.managers = mock(ResourceManagerLocator.class); when(service.managers.getResourceManagerByType(anyString())).thenReturn(manager);
            when(manager.list(anyString(), any(ApiRequest.class))).thenAnswer(call -> rows);
            when(manager.convertResponse(any(Object.class), any())).thenAnswer(call -> call.getArgument(0));
            service.authenticator = authenticator; service.keyAccess = keyAccess;
            ApiContext.newContext().setPolicy(owner); ApiContext.getContext().setApiRequest(originalContext);
            ApiContext.getContext().setIdFormatter(new IdentityFormatter());
        }
        ApiRequest request(Map<String, Object> body) {
            ApiRequest request = new ApiRequest(null, null); request.setMethod("POST"); request.setRequestObject(body); return request;
        }
    }
}
