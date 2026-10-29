package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiCookieAuthentication;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.integration.interfaces.TokenUtil;
import io.cattle.platform.iaas.api.auth.integration.internal.rancher.TokenAuthLookup;
import io.cattle.platform.iaas.api.auth.integration.local.LocalAuthConstants;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.BodyParserRequestHandler;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Test;

public class AiCatalogLaunchHumanInputTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void onlyVerifiedSelectedCookieHasHumanProvenanceBearerAndForgedMetadataDoNot() throws Exception {
        Object before = ArchaiusUtil.getConfiguration().getProperty(SecurityConstants.AUTH_PROVIDER_SETTING);
        try {
            ArchaiusUtil.getConfiguration().setProperty(SecurityConstants.AUTH_PROVIDER_SETTING, LocalAuthConstants.CONFIG);
            for (String scenario : List.of("cookie", "bearer", "wrong-cookie", "failed-identity")) {
                Fixture f = new Fixture("cancel", "{}", true);
                TokenUtil provider = mock(TokenUtil.class); when(provider.getName()).thenReturn(LocalAuthConstants.CONFIG);
                when(provider.isConfigured()).thenReturn(true); when(provider.findAndSetJWT()).thenReturn(true);
                        when(provider.getJWT()).thenReturn("verified-jwt");
                when(provider.getAccountFromJWT()).thenReturn("failed-identity".equals(scenario) ? null : mock(Account.class));
                if ("bearer".equals(scenario)) when(f.http.getCookies()).thenReturn(null);
                if ("wrong-cookie".equals(scenario)) when(f.http.getCookies()).thenReturn(new Cookie[] { new Cookie("token", "other-token") });
                TokenAuthLookup lookup = new TokenAuthLookup(); inject(lookup, "tokenUtils", List.of(provider)); lookup.init();
                lookup.getAccount(f.request);
                assertEquals(scenario, "cookie".equals(scenario), ApiCookieAuthentication.isVerified(f.request));
                if ("cookie".equals(scenario)) {
                    when(f.http.getCookies()).thenReturn(new Cookie[] { new Cookie("token", "changed-after-auth") });
                    assertFalse(ApiCookieAuthentication.isVerified(f.request));
                }
            }
        } finally {
            if (before == null) ArchaiusUtil.getConfiguration().clearProperty(SecurityConstants.AUTH_PROVIDER_SETTING);
            else ArchaiusUtil.getConfiguration().setProperty(SecurityConstants.AUTH_PROVIDER_SETTING, before);
        }
    }

    @Test public void realBodyParserAndValidationKeepOnlyMatchingRoutingActionForHumanCancel() throws Exception {
        Fixture valid = new Fixture("cancel", "{}", true); valid.provenCookie(); valid.pipeline();
        assertEquals("cancelled", valid.service.action(valid.id, valid.request).get("status"));
        assertEquals("cancelled", valid.plan.get("confirmationState"));
        Fixture bodyMismatch = new Fixture("cancel", "{\"action\":\"confirm\"}", true); bodyMismatch.provenCookie(); bodyMismatch.pipeline();
        assertEquals("CatalogLaunchInputInvalid", assertThrows(ClientVisibleException.class,
                () -> bodyMismatch.service.action(bodyMismatch.id, bodyMismatch.request)).getCode());
        assertEquals("needs_user", bodyMismatch.plan.get("status"));
        Fixture extra = new Fixture("cancel", "{\"approved\":true}", true); extra.provenCookie(); extra.pipeline();
        assertEquals("CatalogLaunchInputInvalid", assertThrows(ClientVisibleException.class,
                () -> extra.service.action(extra.id, extra.request)).getCode());
    }

    @Test public void everyKeyAndUnprovenTokenAuthAreForbiddenHumanActionsBeforeDispatch() throws Exception {
        Fixture bearer = new Fixture("cancel", "{}", true); bearer.pipeline();
        assertEquals("AiKeyConfirmationForbidden", assertThrows(ClientVisibleException.class,
                () -> bearer.service.action(bearer.id, bearer.request)).getCode());
        Fixture key = new Fixture("cancel", "{}", true); key.provenCookie(); key.pipeline();
        key.plan.putAll(Map.of("credentialId", 7L, "credentialRevision", 0L, "credentialKind", "apiKey"));
        ApiKeyCredentialContext.attach(key.request, new ApiKeyCredentialContext(7, 42, "apiKey", 0, null));
        assertEquals("AiKeyConfirmationForbidden", assertThrows(ClientVisibleException.class,
                () -> key.service.action(key.id, key.request)).getCode());
        assertEquals("needs_user", bearer.plan.get("status")); assertEquals("needs_user", key.plan.get("status"));
    }

    private static class Fixture {
        final String id = "aicataloglaunch-11111111-1111-1111-1111-111111111111";
        final Map<String, Object> plan = new LinkedHashMap<>();
        final HttpServletRequest http = mock(HttpServletRequest.class);
        final SchemaFactory schemas = mock(SchemaFactory.class); final Policy actor = mock(Policy.class);
        final ApiRequest request; final AiCatalogLaunchPlanService service = new AiCatalogLaunchPlanService() { @Override protected void enabled() { } };
        Fixture(String action, String body, boolean cookie) {
            when(actor.getAuthenticatedAsAccountId()).thenReturn(42L); when(actor.getAccountId()).thenReturn(5L);
            when(http.getCookies()).thenReturn(cookie ? new Cookie[] { new Cookie("token", "verified-jwt") } : null);
            ApiServletContext servlet = mock(ApiServletContext.class); when(servlet.getRequest()).thenReturn(http);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            request = new ApiRequest(servlet, schemas) { @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); } };
            request.setType(AiCatalogLaunchInput.PLAN); request.setId(id); request.setMethod("POST"); request.setAction(action);
            request.setRequestParams(Map.of("action", new String[] { action })); request.setAttribute(AccountConstants.AUTH_TYPE, "TokenAuth");
            SchemaImpl schema = new SchemaImpl(); schema.setId(AiCatalogLaunchInput.PLAN);
            schema.setResourceActions(Map.of("cancel", new Action(null, AiCatalogLaunchInput.PLAN)));
            when(schemas.getSchema(AiCatalogLaunchInput.PLAN)).thenReturn(schema);
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            ApiContext.newContext().setApiRequest(request); ApiContext.getContext().setIdFormatter(formatter); ApiContext.getContext().setPolicy(actor);
            plan.putAll(Map.of("id", id, "actorId", 42L, "accountId", 5L, "status", "needs_user", "confirmationState", "approved",
                    "expiresAt", "2099-01-01T00:00:00Z", "inputsReady", true));
            service.store = new AiCatalogLaunchPlanStore() {
                @Override public <T> T transaction(Supplier<T> operation) { return operation.get(); }
                @Override public Map<String, Object> loadForUpdate(String id) { return plan; }
                @Override public void save(Map<String, Object> value) { }
            };
            service.access = mock(AiCatalogLaunchAccess.class); when(service.access.actor(any())).thenReturn(actor);
            when(service.access.bound(any(), any())).thenReturn(new AiCatalogLaunchAccess.Authorization(new
                    ApiAuthenticator.CurrentAuthorization(actor, schemas), null));
            service.auditSinks = List.of(mock(ApiKeyAuditSink.class)); service.locks = mock(LockManager.class);
            when(service.locks.lock(any(), any())).thenAnswer(call -> ((LockCallback<?>) call.getArgument(1)).doWithLock());
        }
        void provenCookie() { ApiCookieAuthentication.verified(request, "verified-jwt"); }
        void pipeline() throws Exception {
            BodyParserRequestHandler parser = new BodyParserRequestHandler(); parser.init(); JacksonMapper mapper = new JacksonMapper(); mapper.init();
            parser.setJsonMarshaller(mapper); parser.handle(request);
            ValidationHandler validation = new ValidationHandler(); validation.setSupportedMethods(Set.of("GET", "POST", "PUT", "DELETE"));
                    validation.generate(request);
        }
    }

    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
