package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.junit.After;
import org.junit.Test;

public class AiCatalogMetadataAdmissionTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void onlyTheExactTypedCollectionIsReadAndAllowedByBridgeRoute() {
        ApiRequest request = request();
        assertEquals(new ApiKeyOperations.Operation("read", true), ApiKeyOperations.of(request));
        assertTrue(ApiKeyAiBridgeGuard.allows(request));
        request.setId("x"); assertFalse(ApiKeyAiBridgeGuard.allows(request));
        request.setId(null); request.setAction("read"); assertFalse(ApiKeyAiBridgeGuard.allows(request));
        request.setAction(null); request.setLink("files"); assertFalse(ApiKeyAiBridgeGuard.allows(request));
        for (String type : List.of("proxy", "account", "catalog", "catalogTemplate")) {
            request.setType(type); request.setLink(null); request.setMethod("GET"); assertFalse(type, ApiKeyAiBridgeGuard.allows(request));
        }
    }

    @Test public void syntheticAdmissionStillRequiresCurrentOwnerSchemaExpiryAndClosedPolicyChecks() {
        ApiRequest request = request(); Schema schema = mock(Schema.class); when(schema.getCollectionMethods()).thenReturn(List.of("POST"));
        when(request.getSchemaFactory().getSchema("aiCatalogMetadataView")).thenReturn(schema);
        ApiKeyAuthorizationService access = new ApiKeyAuthorizationService(); ApiKeyAuditSink audit = mock(ApiKeyAuditSink.class); access.auditSinks = List.of(audit);
        Policy owner = mock(Policy.class);
        ApiKeyPolicy custom = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of());
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKeyRestricted", 3, custom));
        access.authorize(request, owner); assertEquals("AiCatalogMetadataAdmission", request.getAttribute("apiKey.audit.reason"));
        when(schema.getCollectionMethods()).thenReturn(List.of());
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class, () -> access.authorize(request, owner)).getCode());
        when(schema.getCollectionMethods()).thenReturn(List.of("POST"));
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKeyRestricted", 3,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of())));
        assertEquals("KeyPolicyDenied", assertThrows(ClientVisibleException.class, () -> access.authorize(request, owner)).getCode());
    }

    @Test public void bridgeFeatureAndActualRevisionGuardApplyWithoutChangingGeneralFullBehavior() {
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ApiRequest request = request(); ApiKeyPolicy bridge = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKey", 3, bridge));
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", false);
            assertEquals("AiBridgeDisabled", assertThrows(ClientVisibleException.class, () -> ApiKeyAiBridgeGuard.check(request, bridge)).getCode());
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class, () -> ApiKeyAiBridgeGuard.check(request, bridge)).getCode());
            when(request.getServletContext().getRequest().getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
            ApiKeyAiBridgeGuard.check(request, bridge);
            request.setType("proxy"); request.setMethod("GET");
            ApiKeyAiBridgeGuard.check(request, new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    static ApiRequest request() {
        SchemaFactory schemas = mock(SchemaFactory.class); HttpServletRequest http = mock(HttpServletRequest.class);
        ApiContext.newContext().setIdFormatter(new DefaultIdFormatter().withSchemaFactory(schemas));
        ApiRequest request = new ApiRequest(new ApiServletContext(http, null, null), schemas);
        request.setType("aiCatalogMetadataView"); request.setMethod("POST"); return request;
    }
}
