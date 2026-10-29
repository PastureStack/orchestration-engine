package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.*;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.*;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class AiCatalogMetadataServiceTest {
    static final String ID = "ctm-" + "a".repeat(64);
    @After public void clear() { ApiContext.remove(); }

    @Test public void closedInputRejectsTransportActorCacheIdAndUnboundedQuery() {
        Fixture f = new Fixture();
        for (var body : List.of(Map.of("url", "http://evil"), Map.of("accountId", "1a99"), Map.of("method", "GET"),
                Map.of("templateId", "ctm-41"), Map.of("query", "q".repeat(257)), Map.of("query", "x", "templateId", ID)))
            assertEquals("CatalogMetadataInputInvalid", rejected(() -> f.service.read(f.request(body))));
        verifyNoInteractions(f.keys, f.gateway, f.authenticator);
    }

    @Test public void cookieReadRebuildsActiveProjectAuthorityAndPreservesContext() {
        Fixture f = new Fixture(); ApiRequest request = f.request(Map.of("query", "database"));
        Map<String, Object> result = f.service.read(request);
        assertEquals("catalogTemplate", result.get("resourceType")); assertEquals(true, result.get("resourceTextIsUntrusted"));
        verify(f.gateway).read(eq("1a77"), eq(new AiCatalogMetadataQuery("database", null, null)));
        verify(f.authenticator).currentAuthorization(42, 77, request);
        assertSame(f.context, ApiContext.getContext().getApiRequest()); assertSame(f.owner, ApiContext.getContext().getPolicy());
        verifyNoInteractions(f.audit);
    }

    @Test public void removedInactiveForeignOrMissingProjectAndOwnerSchemaDenyBeforeNetwork() {
        Fixture f = new Fixture(); when(f.project.getState()).thenReturn("inactive");
        assertEquals("OwnerPermissionDenied", rejected(() -> f.service.read(f.request(Map.of()))));
        when(f.project.getState()).thenReturn("active"); when(f.project.getKind()).thenReturn("user");
        assertEquals("OwnerPermissionDenied", rejected(() -> f.service.read(f.request(Map.of()))));
        when(f.project.getKind()).thenReturn("project"); when(f.schema.getCollectionMethods()).thenReturn(List.of());
        assertEquals("OwnerPermissionDenied", rejected(() -> f.service.read(f.request(Map.of()))));
        when(f.schema.getCollectionMethods()).thenReturn(List.of("POST")); when(f.schemas.getSchema(AiCatalogMetadataQuery.TYPE)).thenReturn(null);
        assertEquals("OwnerPermissionDenied", rejected(() -> f.service.read(f.request(Map.of()))));
        verifyNoInteractions(f.gateway);
    }

    @Test public void fullLegacyAndGlobalReadWorkButProjectReadCannotGrantPlatformMetadata() {
        for (ApiKeyPolicy policy : Arrays.asList(null, policy(ApiKeyPolicy.Mode.FULL, List.of()),
                policy(ApiKeyPolicy.Mode.CUSTOM, List.of(rule("global", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.global()))))) {
            Fixture f = new Fixture(); f.service.read(f.keyRequest(policy)); verify(f.gateway).read(eq("1a77"), any());
        }
        Fixture f = new Fixture();
        assertEquals("KeyScopeDenied", rejected(() -> f.service.read(f.keyRequest(policy(ApiKeyPolicy.Mode.CUSTOM,
                List.of(rule("project", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a77"))))))));
        verifyNoInteractions(f.gateway);
    }

    @Test public void expiryClosedAndExplicitDenyWinOverGrants() {
        for (ApiKeyPolicy policy : List.of(policy(ApiKeyPolicy.Mode.CLOSED, List.of()),
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, Instant.EPOCH, List.of()),
                policy(ApiKeyPolicy.Mode.CUSTOM, List.of(rule("grant", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.global()),
                        rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.global()))))) {
            Fixture f = new Fixture(); String reason = rejected(() -> f.service.read(f.keyRequest(policy)));
            assertTrue(Set.of("KeyPolicyDenied", "ApiKeyExpired").contains(reason)); verifyNoInteractions(f.gateway);
        }
    }

    @Test public void incompleteFineGrainedSelectorsFailClosedWithActionableCodeAndDoNotHideGlobalDeny() {
        for (var effect : ApiKeyPolicy.Effect.values()) {
            Fixture f = new Fixture();
            assertEquals("CatalogMetadataFineGrainedUnsupported", rejected(() -> f.service.read(f.keyRequest(policy(ApiKeyPolicy.Mode.CUSTOM,
                    List.of(rule("template", effect, ApiKeyPolicy.Scope.resource("catalogTemplate", ID))))))));
            verifyNoInteractions(f.gateway);
        }
        Fixture f = new Fixture();
        assertEquals("KeyPolicyDenied", rejected(() -> f.service.read(f.keyRequest(policy(ApiKeyPolicy.Mode.CUSTOM,
                List.of(rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.global()),
                        rule("template", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("catalogTemplate", ID))))))));
    }

    @Test public void actualBridgeGuardAndServiceRequireExpectedRevisionOnEveryRead() {
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
            for (String expected : Arrays.asList(null, "2", "3")) {
                Fixture f = new Fixture(); ApiRequest request = f.keyRequest(policy); HttpServletRequest http = mock(HttpServletRequest.class);
                when(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn(expected);
                request.setRequestServletContext(new ApiServletContext(http, null, null));
                if ("3".equals(expected)) { ApiKeyAiBridgeGuard.check(request, policy); f.service.read(request); verify(f.gateway).read(eq("1a77"), any()); }
                else { assertEquals("ApiKeyPolicyChanged", rejected(() -> ApiKeyAiBridgeGuard.check(request, policy)));
                    assertEquals("ApiKeyPolicyChanged", rejected(() -> f.service.read(request))); verifyNoInteractions(f.gateway); }
            }
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void liveHelperIsReenteredAndCredentialRevocationPreventsNextRead() {
        Fixture f = new Fixture(); ApiRequest request = f.keyRequest(policy(ApiKeyPolicy.Mode.FULL, List.of()));
        f.service.read(request); doThrow(new ClientVisibleException(403, "ApiKeyRevoked")).when(f.keys).actor(request);
        assertEquals("ApiKeyRevoked", rejected(() -> f.service.read(request)));
        verify(f.keys, times(2)).actor(request); verify(f.gateway, times(1)).read(anyString(), any());
    }

    @Test public void keyDecisionUsesFreshSecretFreeRequestAndAuditFailureStopsRead() {
        Fixture f = new Fixture(); ApiRequest request = f.keyRequest(policy(ApiKeyPolicy.Mode.FULL, List.of()));
        request.setAttribute("apiKey.audit.admitted", true); f.service.read(request);
        ArgumentCaptor<ApiRequest> captured = ArgumentCaptor.forClass(ApiRequest.class); verify(f.audit).recordDecision(captured.capture(), eq(f.live));
        ApiRequest event = captured.getValue(); assertNotSame(request, event); assertNull(event.getAttribute("apiKey.audit.admitted"));
        assertEquals("ALLOW", event.getAttribute("apiKey.audit.decision")); assertEquals("read", event.getAttribute("apiKey.audit.operation"));
        assertEquals(3L, event.getAttribute("apiKey.audit.policyRevision")); assertEquals(42L, event.getAttribute("apiKey.audit.verifiedPrincipalAccountId"));
        assertNull(event.getRequestObject()); assertTrue(event.getRequestParams().isEmpty());
        Fixture denied = new Fixture(); doThrow(new IllegalStateException("private endpoint password")).when(denied.audit).recordDecision(any(), any());
        assertEquals("CatalogMetadataAuditUnavailable", rejected(() -> denied.service.read(denied.keyRequest(policy(ApiKeyPolicy.Mode.FULL, List.of())))));
        verifyNoInteractions(denied.gateway);
    }

    @Test public void projectionNeverReturnsRawConfigCredentialsDefaultsLinksOrCacheIds() {
        var row = new LinkedHashMap<String, Object>(safeRow()); row.put("description", "x".repeat(1100));
        for (String secret : List.of("password", "files", "questions", "defaultAnswers", "readme", "url", "links", "rowId")) row.put(secret, "private-secret");
        var projected = AiCatalogMetadataService.project(Map.of("resourceType", "catalogTemplate", "data", List.of(row)), new AiCatalogMetadataQuery(null, null, null));
        assertFalse(projected.toString().contains("private-secret")); Map<?, ?> item = (Map<?, ?>) ((List<?>) projected.get("data")).getFirst();
        assertEquals(1024, item.get("description").toString().length()); assertEquals(List.of("description"), item.get("truncatedFields"));
        assertEquals("CatalogMetadataUnavailable", rejected(() -> AiCatalogMetadataService.project(
                Map.of("resourceType", "catalogTemplate", "data", List.of(safeRow(), safeRow())), new AiCatalogMetadataQuery(null, null, null))));
        assertEquals("CatalogMetadataUnavailable", rejected(() -> AiCatalogMetadataService.project(
                Map.of("resourceType", "catalogTemplate", "data", List.of(safeRow())), new AiCatalogMetadataQuery(null, null, "ctm-" + "b".repeat(64)))));
    }

    @Test public void versionsRequireAParentAndProjectOnlySafeSelectionMetadata() {
        Fixture f = new Fixture(); String version = "cvm-" + "b".repeat(64);
        for (var body : List.of(Map.of("versions", true), Map.of("versions", false), Map.of("templateId", ID, "versionId", version),
                Map.of("templateId", ID, "versions", true, "query", "x")))
            assertEquals("CatalogMetadataInputInvalid", rejected(() -> f.service.read(f.request(body))));
        var row = new LinkedHashMap<String, Object>(Map.of("id", version, "type", "catalogTemplateVersion", "templateId", ID,
                "version", "1.0.0", "revision", 5, "minimumRancherVersion", "1.6.0", "files", "PRIVATE-COMPOSE", "questions", "PRIVATE-DEFAULT"));
        var query = new AiCatalogMetadataQuery(null, null, ID, true, version);
        Map<String, Object> safe = AiCatalogMetadataService.project(Map.of("resourceType", "catalogTemplateVersion", "data", List.of(row)), query);
        assertFalse(safe.toString().contains("PRIVATE")); assertEquals("catalogTemplateVersion", safe.get("resourceType"));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) safe.get("data")).getFirst(); assertEquals(5, item.get("revision")); assertEquals(ID, item.get("templateId"));
        row.put("templateId", "ctm-" + "c".repeat(64));
        assertEquals("CatalogMetadataUnavailable", rejected(() -> AiCatalogMetadataService.project(Map.of("resourceType", "catalogTemplateVersion", "data", List.of(row)), query)));
        row.put("templateId", ID); row.put("revision", 0.5);
        assertEquals("CatalogMetadataUnavailable", rejected(() -> AiCatalogMetadataService.project(Map.of("resourceType", "catalogTemplateVersion", "data", List.of(row)), query)));
    }

    static ApiKeyPolicy policy(ApiKeyPolicy.Mode mode, List<ApiKeyPolicy.Rule> rules) {
        return new ApiKeyPolicy(mode, ApiKeyPolicy.Effect.DENY, null, rules);
    }
    static ApiKeyPolicy.Rule rule(String id, ApiKeyPolicy.Effect effect, ApiKeyPolicy.Scope scope) {
        return new ApiKeyPolicy.Rule(id, effect, scope, Set.of("read"));
    }
    @Test public void templateKeyIsRequiredSafeAndIndependentFromDuplicateDisplayNames() {
        var first = new LinkedHashMap<String,Object>(safeRow()); var second = new LinkedHashMap<String,Object>(safeRow());
        first.put("templateKey","mysql-small"); second.put("templateKey","mysql-large"); second.put("id","ctm-"+"b".repeat(64));
        var query = new AiCatalogMetadataQuery(null,null,null);
        var result = AiCatalogMetadataService.project(Map.of("resourceType","catalogTemplate","data",List.of(first,second)),query);
        assertTrue(result.toString().contains("mysql-small")); assertTrue(result.toString().contains("mysql-large"));
        for(String key:List.of("", ".", "..", "/tmp/cache/template", "C:\\cache\\template", "https://private.example/template", "key\nother\nlast")) {
            first.put("templateKey",key);
            assertEquals("CatalogMetadataUnavailable",rejected(()->AiCatalogMetadataService.project(Map.of("resourceType","catalogTemplate","data",List.of(first)),query)));
        }
    }
    static Map<String, Object> safeRow() { return Map.of("id", ID, "type", "catalogTemplate", "name", "database", "templateKey", "database-small", "catalogScope", "project"); }
    static String rejected(Runnable action) {
        try { action.run(); fail("must reject"); return null; } catch (ClientVisibleException rejected) { return rejected.getCode(); }
    }
    static class Fixture {
        final AiCatalogMetadataService service = new AiCatalogMetadataService();
        final AiKeyAuthorization keys = mock(AiKeyAuthorization.class);
        final ApiAuthenticator authenticator = mock(ApiAuthenticator.class);
        final Policy owner = mock(Policy.class), live = mock(Policy.class);
        final SchemaFactory schemas = mock(SchemaFactory.class); final Schema schema = mock(Schema.class);
        final Account project = mock(Account.class); final AiCatalogMetadataGateway gateway = mock(AiCatalogMetadataGateway.class);
        final ApiKeyAuditSink audit = mock(ApiKeyAuditSink.class); final ApiRequest context = new ApiRequest(null, null);
        Fixture() {
            service.keys = keys; service.authenticator = authenticator; service.gateway = gateway; service.auditSinks = List.of(audit);
            service.objects = mock(ObjectManager.class);
            when(keys.actor(any())).thenReturn(owner); when(owner.getAccountId()).thenReturn(77L); when(owner.getAuthenticatedAsAccountId()).thenReturn(42L);
            when(live.getAccountId()).thenReturn(77L); when(live.getAuthenticatedAsAccountId()).thenReturn(42L);
            when(authenticator.currentAuthorization(eq(42L), eq(77L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(live, schemas));
            when(service.objects.loadResource(Account.class, 77L)).thenReturn(project);
            when(project.getState()).thenReturn("active"); when(project.getKind()).thenReturn("project");
            when(schemas.getSchema(AiCatalogMetadataQuery.TYPE)).thenReturn(schema); when(schema.getCollectionMethods()).thenReturn(List.of("POST"));
            when(gateway.read(anyString(), any())).thenReturn(Map.of("resourceType", "catalogTemplate", "data", List.of(safeRow())));
            ApiContext.newContext().setPolicy(owner); ApiContext.getContext().setApiRequest(context);
            ApiContext.getContext().setIdFormatter(new DefaultIdFormatter().withSchemaFactory(schemas));
        }
        ApiRequest request(Map<String, ?> body) {
            ApiRequest request = new ApiRequest(null, schemas); request.setType(AiCatalogMetadataQuery.TYPE); request.setMethod("POST"); request.setRequestObject(body); return request;
        }
        ApiRequest keyRequest(ApiKeyPolicy policy) {
            ApiRequest request = request(Map.of()); ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKeyRestricted", 3, policy)); return request;
        }
    }
}
