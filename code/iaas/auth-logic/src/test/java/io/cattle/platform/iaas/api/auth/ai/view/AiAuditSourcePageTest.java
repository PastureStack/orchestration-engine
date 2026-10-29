package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.AuditLogRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.*;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AiAuditSourcePageTest {
    Object previous;
    @Before public void setup() {
        previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
    }
    @After public void cleanup() {
        ApiContext.remove();
        if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
    }

    @Test public void fourSourcesUseRealGuardEvaluatorRowFilterAndNarrowScalarProjection() throws Exception {
        for (String type : List.of("project", "account", "apiKey", "auditLog")) {
            Fixture f = new Fixture(type); Map<String, Object> result = f.service.read(f.request());
            assertEquals(type, result.get("resourceType")); assertEquals(true, result.get("sourcePageResult"));
            assertEquals(1, ((List<?>) result.get("data")).size());
            assertFalse(result.toString().contains("PRIVATE")); assertFalse(result.toString().contains("requestObject"));
            Map<?, ?> authority = (Map<?, ?>) result.get("nativeAuthority");
            assertEquals("1a1", authority.get("actorId")); assertEquals("1c8", authority.get("credentialId"));
            assertEquals(3L, authority.get("credentialRevision"));
            verify(f.accessSink).recordDecision(any(), eq(f.owner));
            verify(f.authenticator, atLeastOnce()).currentAuthorization(eq(1L), eq(1L), any());
            assertNull(f.request().getAttribute(AiResourceViewService.DOMAIN_READ_ATTRIBUTE));
        }
    }

    @Test public void eachSourcePreservesRoleDenialExplicitKeyDenialAndForeignPointIdentity() throws Exception {
        for (String type : List.of("project", "account", "apiKey", "auditLog")) {
            Fixture role = new Fixture(type); when(role.schema.getCollectionMethods()).thenReturn(List.of());
            when(role.schema.getResourceMethods()).thenReturn(List.of());
            assertEquals("OwnerPermissionDenied", failure(() -> role.service.read(role.request()))); verifyNoInteractions(role.manager);
            Fixture deny = new Fixture(type); deny.policy(true);
            if ("apiKey".equals(type)) assertEquals("KeyPolicyDenied", failure(() -> deny.service.read(deny.request())));
            else assertTrue(((List<?>) deny.service.read(deny.request()).get("data")).isEmpty());
        }
        Fixture f = new Fixture("apiKey"); Map<String, Object> input = f.body(); input.put("resourceId", "1c3");
        CredentialRecord other = new CredentialRecord(); other.setId(3L); other.setAccountId(1L);
        when(f.objects.loadResource("apiKey", 3L)).thenReturn(other);
        ApiRequest foreign = f.request(); foreign.setRequestObject(input);
        assertEquals("KeyScopeDenied", failure(() -> f.service.read(foreign))); verifyNoInteractions(f.manager);
        ApiRequest mint = f.request(); mint.setType("apiKey"); mint.setRequestObject(Map.of("publicValue", "forged"));
        ApiKeyCredentialContext.attach(mint, new ApiKeyCredentialContext(8, 1, "apiKey", 3,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, f.policy.getRules())));
        assertEquals("ApiKeyRestrictedDelegationUnsupported", failure(() -> f.access.authorize(mint, f.owner)));
    }

    @Test public void everySourcePageReloadsLiveCredentialRoleAndRejectsRevisionRevocationOrExpiry() throws Exception {
        for (String type : List.of("project", "account", "apiKey", "auditLog")) {
            Fixture f = new Fixture(type); f.service.read(f.request());
            f.credential.setState("inactive");
            assertEquals("ApiKeyRevoked", failure(() -> f.service.read(f.request())));
            f.credential.setState("active"); f.credential.getData().put(ApiKeyPolicyCodec.REVISION, 4L);
            assertEquals("ApiKeyPolicyChanged", failure(() -> f.service.read(f.request())));
            f.credential.getData().put(ApiKeyPolicyCodec.REVISION, 3L);
            when(f.http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("4");
            assertEquals("ApiKeyPolicyChanged", failure(() -> f.service.read(f.request())));
            when(f.http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
            f.credential.setData(new ApiKeyPolicyCodec().store(f.credential,
                    new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, java.time.Instant.EPOCH,
                            List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE), 3));
            assertEquals("ApiKeyExpired", failure(() -> f.service.read(f.request())));
        }
    }

    @Test public void sourcePagesHaveNoCallerControlledRouteFieldsIdentityOrUnboundedInput() throws Exception {
        Fixture f = new Fixture("auditLog");
        for (Map<String, Object> page : List.of(Map.<String, Object>of("limit", 1001),
                Map.<String, Object>of("url", "http://foreign/"), Map.<String, Object>of("actorId", "1a1"),
                Map.<String, Object>of("from", "2020-01-01T00:00:00Z", "to", "2030-01-01T00:00:00Z"))) {
            ApiRequest request = f.request(); request.setRequestObject(Map.of("resourceType", "auditLog", "sourcePage", page));
            assertEquals("ResourceViewInputInvalid", failure(() -> f.service.read(request)));
        }
        ApiRequest unsupported = f.request(); Map<String, Object> input = f.body(); input.put("resourceType", "secret"); unsupported.setRequestObject(input);
        assertEquals("ResourceViewInputInvalid", failure(() -> f.service.read(unsupported)));
        ApiRequest request = f.request(); input = f.body();
        input.put("sourcePage", Map.of("marker", "1c2", "from", "2026-10-01T00:00:00Z", "to", "2026-10-02T00:00:00Z"));
        request.setRequestObject(input); ApiRequest wrongMarker = request;
        assertEquals("ResourceViewInputInvalid", failure(() -> f.service.read(wrongMarker)));
        for (String type : List.of("account", "apiKey", "auditLog")) {
            ApiRequest raw = f.request(); raw.setType(type); raw.setMethod("GET");
            raw.setRequestObject(Map.of(AiResourceViewService.DOMAIN_READ_ATTRIBUTE, true));
            assertEquals("AiBridgeRouteDenied", failure(() -> f.access.authorize(raw, f.owner)));
        }
        verifyNoInteractions(f.manager);
    }

    static String failure(Runnable action) { return assertThrows(ClientVisibleException.class, action::run).getCode(); }

    @Test public void newQueryAdmissionIsReadOnlyAndNeverOpensRawAuditOrCredentialRoutes() throws Exception {
        Fixture f = new Fixture("auditLog"); ApiRequest query = f.request(); query.setType("aiAuditQuery");
        assertEquals("read", ApiKeyOperations.of(query).id()); f.access.authorize(query, f.owner);
        verifyNoInteractions(f.manager);
        when(f.schema.getCollectionMethods()).thenReturn(List.of("GET"));
        assertEquals("OwnerPermissionDenied", failure(() -> f.access.authorize(query, f.owner)));
        when(f.schema.getCollectionMethods()).thenReturn(List.of("GET", "POST"));
        query.setAction("export"); assertEquals("AiBridgeRouteDenied", failure(() -> f.access.authorize(query, f.owner)));
        query.setAction(null); query.setMethod("GET"); assertEquals("AiBridgeRouteDenied", failure(() -> f.access.authorize(query, f.owner)));
    }

    @Test public void authorizedManagerCannotSubstituteAnotherTypeOrAnotherPointId() throws Exception {
        Fixture f = new Fixture("apiKey");
        when(f.manager.convertResponse(eq(f.row), any())).thenReturn(new ResourceImpl("1c3", "apiKey", Map.of("accountId", "1a1")));
        assertEquals("OwnerPermissionDenied", failure(() -> f.service.read(f.request())));
        when(f.manager.convertResponse(eq(f.row), any())).thenReturn(new ResourceImpl("1c2", "token", Map.of("accountId", "1a1")));
        assertEquals("OwnerPermissionDenied", failure(() -> f.service.read(f.request())));
    }
    static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    static final class Fixture {
        final String type;
        final AiResourceViewService service = new AiResourceViewService();
        final ApiKeyAuthorizationService access = new ApiKeyAuthorizationService();
        final ApiKeyTargetResolver targets = new ApiKeyTargetResolver();
        final ApiKeyAuditSink accessSink = mock(ApiKeyAuditSink.class);
        final ApiAuthenticator authenticator = mock(ApiAuthenticator.class);
        final ObjectManager objects = mock(ObjectManager.class);
        final Policy owner = mock(Policy.class);
        final Schema schema = mock(Schema.class);
        final SchemaFactory schemas = mock(SchemaFactory.class);
        final ResourceManager manager = mock(ResourceManager.class);
        final HttpServletRequest http = mock(HttpServletRequest.class);
        final CredentialRecord credential = new CredentialRecord();
        final Object row;
        ApiKeyPolicy policy;
        Fixture(String type) throws Exception {
            this.type = type; ApiContext.newContext().setPolicy(owner);
            when(owner.getAuthenticatedAsAccountId()).thenReturn(1L); when(owner.getAccountId()).thenReturn(1L);
            when(owner.authorizeObject(any())).thenAnswer(call -> call.getArgument(0));
            when(schemas.getSchema(anyString())).thenReturn(schema); when(schemas.getBaseType(anyString())).thenAnswer(call -> call.getArgument(0));
            when(schema.getCollectionMethods()).thenReturn(List.of("GET", "POST")); when(schema.getResourceMethods()).thenReturn(List.of("GET"));
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("project", "a", "apiKey", "c", "auditLog", "al")); ApiContext.getContext().setIdFormatter(formatter);
            credential.setId(8L); credential.setAccountId(1L); credential.setKind("apiKey"); credential.setState("active");
            when(objects.loadResource(Credential.class, 8L)).thenReturn(credential);
            when(authenticator.currentAuthorization(anyLong(), anyLong(), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, schemas));
            set(targets, "objectManager", objects); set(targets, "idFormatter", formatter);
            ProjectAccessService projects = mock(ProjectAccessService.class); set(targets, "projectAccess", projects);
            if ("apiKey".equals(type)) { CredentialRecord key = new CredentialRecord(); key.setId(2L); key.setAccountId(1L); row = key; }
            else if ("auditLog".equals(type)) { AuditLogRecord log = new AuditLogRecord(); log.setId(2L); log.setAccountId(1L); row = log; }
            else { AccountRecord account = new AccountRecord(); account.setId(2L); account.setKind(type); row = account;
                when(projects.checkAccess(eq(account), any())).thenReturn(account); }
            when(objects.getType(row)).thenReturn(type);
            when(objects.loadResource(eq(type), anyLong())).thenReturn(row);
            ResourceManagerLocator locator = mock(ResourceManagerLocator.class); when(locator.getResourceManagerByType(anyString())).thenReturn(manager);
            set(access, "targets", targets); set(access, "auditSinks", List.of(accessSink));
            set(access, "resourceManagers", locator); set(access, "objectManager", objects);
            when(manager.supportsScopedCollectionQuery(access)).thenReturn(true);
            when(manager.list(anyString(), any(ApiRequest.class))).thenAnswer(call -> access.filterCollection(call.getArgument(1), List.of(row)));
            when(manager.getById(anyString(), anyString(), any())).thenReturn(row);
            Map<String, Object> fields = new LinkedHashMap<>(Map.of("name", "Visible", "kind", type, "accountId", "1a1",
                    "created", "2026-10-01T12:00:00Z", "requestObject", "PRIVATE", "data", "PRIVATE", "privateValue", "PRIVATE"));
            ResourceImpl converted = new ResourceImpl(wire(), type, fields);
            when(manager.convertResponse(eq(row), any())).thenReturn(converted);
            AiKeyAuthorization live = new AiKeyAuthorization(); set(live, "objects", objects);
            set(live, "authenticator", authenticator); set(live, "targets", targets);
            service.keyAuthorization = live; service.keyAccess = access; service.authenticator = authenticator;
            service.managers = locator; service.objects = objects;
            when(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3"); policy(false);
        }
        String wire() { return AiAuditSourcePage.wire(type, 2L); }
        void policy(boolean deny) {
            policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                    List.of(new ApiKeyPolicy.Rule("read", deny ? ApiKeyPolicy.Effect.DENY : ApiKeyPolicy.Effect.ALLOW,
                            ApiKeyPolicy.Scope.resource(type, wire()), Set.of("read"))), ApiKeyPolicy.Purpose.AI_BRIDGE);
            credential.setData(new ApiKeyPolicyCodec().store(credential, policy, 3));
        }
        Map<String, Object> body() {
            Map<String, Object> page = new LinkedHashMap<>(); page.put("limit", 1000);
            if ("auditLog".equals(type)) { page.put("from", "2026-10-01T00:00:00Z"); page.put("to", "2026-10-02T00:00:00Z"); }
            Map<String, Object> body = new LinkedHashMap<>(Map.of("resourceType", type, "sourcePage", page));
            if ("apiKey".equals(type)) body.put("resourceId", wire()); return body;
        }
        ApiRequest request() {
            ApiRequest request = new ApiRequest(null, schemas); request.setType("aiResourceView"); request.setMethod("POST"); request.setRequestObject(body());
            ApiServletContext servlet = mock(ApiServletContext.class); when(servlet.getRequest()).thenReturn(http); request.setRequestServletContext(servlet);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(8, 1, "apiKey", 3, policy));
            ApiContext.getContext().setApiRequest(request); return request;
        }
    }
}
