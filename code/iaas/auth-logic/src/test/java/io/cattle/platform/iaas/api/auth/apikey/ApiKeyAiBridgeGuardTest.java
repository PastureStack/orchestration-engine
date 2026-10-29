package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.iaas.api.auth.ai.view.AiResourceViewService;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

public class ApiKeyAiBridgeGuardTest {
    @Before public void setup() { ApiContext.newContext(); }
    @After public void cleanup() { ApiContext.remove(); }
    @Test public void safeViewAllowsOnlyItsInternalReadAndNeverOpensDirectSensitiveRoutes() {
        assertTrue(ApiKeyAiBridgeGuard.allows(request("aiResourceView", "POST", null, null)));
        for (String type : AiResourceViewService.READ_TYPES) {
            ApiRequest internal = request(type, "GET", "1", null);
            internal.setAttribute(AiResourceViewService.DOMAIN_READ_ATTRIBUTE, Boolean.TRUE);
            assertTrue(type, ApiKeyAiBridgeGuard.allows(internal));
            internal.setAction("export"); assertFalse(ApiKeyAiBridgeGuard.allows(internal));
            internal.setAction(null); internal.setLink("secretvalues"); assertFalse(ApiKeyAiBridgeGuard.allows(internal));
            internal.setLink(null); internal.setMethod("PUT"); assertFalse(ApiKeyAiBridgeGuard.allows(internal));
        }
        for (String type : List.of("certificate", "setting", "apiKey", "registryCredential")) {
            ApiRequest external = request(type, "GET", "1", null);
            external.setRequestObject(java.util.Map.of(AiResourceViewService.DOMAIN_READ_ATTRIBUTE, true));
            assertFalse(ApiKeyAiBridgeGuard.allows(external));
        }
    }
    @Test public void exactRouteAllowlistNeverIncludesGenericMutationConfirmationOrSecrets() {
        for (String type : List.of("project", "stack", "service")) {
            assertTrue(ApiKeyAiBridgeGuard.allows(request(type,"GET",null,null)));
            assertTrue(ApiKeyAiBridgeGuard.allows(request(type,"GET","1",null)));
            assertFalse(ApiKeyAiBridgeGuard.allows(request(type,"PUT","1",null)));
        }
        for (String type : List.of("token","apiKey","credential","schema","setting","container"))
            assertFalse(ApiKeyAiBridgeGuard.allows(request(type,"GET",null,null)));
        assertTrue(ApiKeyAiBridgeGuard.allows(request("aiServiceUpgradePlan","POST",null,null)));
        assertTrue(ApiKeyAiBridgeGuard.allows(request("aiServiceUpgradePlan","POST","aiplan-x","apply")));
        assertTrue(ApiKeyAiBridgeGuard.allows(request("aiServiceUpgradePlan","GET","aiplan-x",null)));
        assertFalse(ApiKeyAiBridgeGuard.allows(request("aiServiceUpgradePlan","GET",null,null)));
        assertFalse(ApiKeyAiBridgeGuard.allows(request("aiServiceUpgradePlan","POST","aiplan-x","confirm")));
        assertFalse(ApiKeyAiBridgeGuard.allows(request("service","POST","1","upgrade")));
        ApiRequest link=request("service","GET","1",null); link.setLink("logs");
        assertFalse(ApiKeyAiBridgeGuard.allows(link));
        for (String type : List.of("aiToolCallEvent","aiServiceDescriptionUpdate"))
            assertTrue(ApiKeyAiBridgeGuard.allows(request(type,"POST",null,null)));
    }
    @Test public void disabledPurposeFailsClosedButGeneralFullRemainsUnchanged() {
        Object previous=ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled",false);
            ApiKeyPolicy full=new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,ApiKeyPolicy.Effect.ALLOW,null,List.of());
            ApiKeyAiBridgeGuard.check(request("service","PUT","1",null),full);
            ApiKeyPolicy bridge=new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,ApiKeyPolicy.Effect.ALLOW,null,List.of(),ApiKeyPolicy.Purpose.AI_BRIDGE);
            assertEquals("AiBridgeDisabled",assertThrows(ClientVisibleException.class,()->ApiKeyAiBridgeGuard.check(request("service","GET","1",null),bridge)).getCode());
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled",true);
            ApiKeyAiBridgeGuard.check(request("service","GET","1",null),bridge);
            assertEquals("AiBridgeRouteDenied",assertThrows(ClientVisibleException.class,()->ApiKeyAiBridgeGuard.check(request("service","PUT","1",null),bridge)).getCode());
        } finally {
            if(previous==null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled",previous);
        }
    }
    @Test public void onlyBootstrapMayOmitExpectedRevisionAndOtherRequestsMatchVerifiedContext() {
        Object previous=ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled",true);
            ApiKeyPolicy bridge=new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,ApiKeyPolicy.Effect.ALLOW,null,List.of(),ApiKeyPolicy.Purpose.AI_BRIDGE);
            ApiRequest bootstrap=request("project","GET",null,null);
            when(bootstrap.getServletContext().getRequest().getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn(null);
            ApiKeyAiBridgeGuard.check(bootstrap,bridge);
            for(String method:List.of("GET","POST")) {
                ApiRequest request=request(method.equals("GET")?"service":"aiServiceDescriptionUpdate",method,null,null);
                when(request.getServletContext().getRequest().getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn(null);
                assertEquals("ApiKeyPolicyChanged",assertThrows(ClientVisibleException.class,()->ApiKeyAiBridgeGuard.check(request,bridge)).getCode());
                when(request.getServletContext().getRequest().getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("2");
                assertEquals("ApiKeyPolicyChanged",assertThrows(ClientVisibleException.class,()->ApiKeyAiBridgeGuard.check(request,bridge)).getCode());
                when(request.getServletContext().getRequest().getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
                ApiKeyAiBridgeGuard.check(request,bridge);
            }
        } finally {
            if(previous==null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled",previous);
        }
    }
    private ApiRequest request(String type,String method,String id,String action) {
        HttpServletRequest http=mock(HttpServletRequest.class);
        when(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
        ApiRequest request=new ApiRequest(new ApiServletContext(http,null,null),null);
        request.setType(type); request.setMethod(method); request.setId(id); request.setAction(action);
        ApiKeyCredentialContext.attach(request,new ApiKeyCredentialContext(7,42,"apiKeyRestricted",3,null));
        return request;
    }
}
