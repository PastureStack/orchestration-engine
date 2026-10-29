package io.cattle.platform.iaas.api.auth.ai;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.iaas.api.auth.apikey.*;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.time.*;
import java.util.*;
import org.junit.*;

public class AiKeyAuthorizationTest {
    AiKeyAuthorization guard;
    Policy owner;
    CredentialRecord credential;
    ServiceRecord service;
    ApiRequest request;
    Schema schema;
    Instant now = Instant.parse("2026-10-29T00:00:00Z");
    @Before public void setup() {
        guard = new AiKeyAuthorization(); guard.objects = mock(ObjectManager.class);
        guard.authenticator = mock(ApiAuthenticator.class); guard.targets = mock(ApiKeyTargetResolver.class);
        guard.clock = Clock.fixed(now, ZoneOffset.UTC);
        owner = mock(Policy.class); when(owner.getAccountId()).thenReturn(77L); when(owner.getAuthenticatedAsAccountId()).thenReturn(42L);
        ApiContext.newContext().setPolicy(owner);
        credential = new CredentialRecord(); credential.setId(7L); credential.setAccountId(42L);
        credential.setKind("apiKeyRestricted"); credential.setState("active");
        when(guard.objects.loadResource(Credential.class, 7L)).thenReturn(credential);
        service = new ServiceRecord(); service.setId(10L); service.setAccountId(77L); service.setKind("service");
        when(owner.authorizeObject(service)).thenReturn(service);
        SchemaFactory factory = mock(SchemaFactory.class); schema = mock(Schema.class);
        when(factory.getSchema("service")).thenReturn(schema); when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
        Field description=mock(Field.class); when(description.isUpdate()).thenReturn(true);
        when(schema.getResourceFields()).thenReturn(Map.of("description", description));
        when(schema.getResourceActions()).thenReturn(Map.of("upgrade", new Action(), "finishupgrade", new Action()));
        when(guard.authenticator.currentAuthorization(eq(42L), eq(77L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, factory));
        when(guard.targets.resolveObject("service", service, owner)).thenReturn(ApiKeyPolicyEvaluator.Target.stackResource("service", "1s10", "1a77", "1st3"));
        request = new ApiRequest(null, factory); request.setType("aiServiceUpgradePlan"); request.setMethod("POST"); request.setAction("apply");
        ApiContext.getContext().setApiRequest(request);
        policy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st3"), Set.of("upgrade"))));
    }
    @After public void cleanup() { ApiContext.remove(); }
    void policy(ApiKeyPolicy.Mode mode, ApiKeyPolicy.Effect effect, Instant expiry, List<ApiKeyPolicy.Rule> rules) {
        ApiKeyPolicy policy = new ApiKeyPolicy(mode, effect, expiry, rules);
        credential.setData(new ApiKeyPolicyCodec().store(credential, policy, 3));
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, policy));
    }
    String denied(Runnable action) { return assertThrows(ClientVisibleException.class, action::run).getCode(); }

    @Test public void customUsesLiveOwnerAndRealServiceScope() {
        assertSame(owner, guard.authorizeService(request, service, "upgrade"));
        assertEquals("KeyScopeDenied", denied(() -> guard.authorizeService(request, service, "update")));
        when(schema.getResourceActions()).thenReturn(Map.of());
        assertEquals("OwnerPermissionDenied", denied(() -> guard.authorizeService(request, service, "upgrade")));
    }
    @Test public void fullIsStillBoundByOwnerAndClosedExpiryRevocationRevisionAreRejected() {
        policy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of());
        assertSame(owner, guard.authorizeService(request, service, "upgrade"));
        when(owner.authorizeObject(service)).thenReturn(null);
        assertEquals("OwnerPermissionDenied", denied(() -> guard.authorizeService(request, service, "upgrade")));
        when(owner.authorizeObject(service)).thenReturn(service);
        policy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of());
        assertEquals("KeyPolicyDenied", denied(() -> guard.actor(request)));
        policy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, now, List.of());
        assertEquals("ApiKeyExpired", denied(() -> guard.actor(request)));
        policy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of());
        credential.getData().put(ApiKeyPolicyCodec.REVISION, 4L);
        assertEquals("ApiKeyPolicyChanged", denied(() -> guard.actor(request)));
        credential.setState("inactive");
        assertEquals("ApiKeyRevoked", denied(() -> guard.actor(request)));
    }
    @Test public void humanConfirmationChecksTheActualBoundKeyNotTheCurrentCookie() {
        Map<String,Object> plan = new HashMap<>(Map.of("credentialId",7L,"credentialRevision",3L,"credentialKind","apiKeyRestricted","actorId",42L));
        ApiRequest human = new ApiRequest(null, null);
        guard.authorizeBound(plan, service, "upgrade", human);
        credential.setState("inactive");
        assertEquals("ApiKeyRevoked", denied(() -> guard.authorizeBound(plan, service, "upgrade", human)));
        when(owner.getAuthenticatedAsAccountId()).thenReturn(43L);
        assertEquals("OwnerPermissionDenied", denied(() -> guard.authorizeBound(plan, service, "upgrade", human)));
    }
    @Test public void typedHumanApprovalReevaluatesTheActualBoundKeyOperationAndRevision() {
        Map<String, Object> plan = new HashMap<>(Map.of("credentialId", 7L, "credentialRevision", 3L, "credentialKind", "apiKeyRestricted", "actorId", 42L));
        ApiRequest domain = new ApiRequest(null, request.getSchemaFactory()); domain.setType("service"); domain.setMethod("PUT"); domain.setId("10");
        domain.setAttribute("ai.resource.change.domain.descriptor",
                io.cattle.platform.iaas.api.auth.change.AiResourceChangeDescriptor.require("service", "update_metadata"));
        when(guard.targets.resolve(domain, owner)).thenReturn(List.of(ApiKeyPolicyEvaluator.Target.stackResource("service", "1s10", "1a77", "1st3")));
        assertEquals("KeyScopeDenied", denied(() -> guard.authorizeBoundDomain(plan, domain)));
        policy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st3"), Set.of("update"))));
        guard.authorizeBoundDomain(plan, domain);
        credential.getData().put(ApiKeyPolicyCodec.REVISION, 4L);
        assertEquals("ApiKeyPolicyChanged", denied(() -> guard.authorizeBoundDomain(plan, domain)));
    }
    @Test public void boundResourceAuthorizationCannotBeUsedAsAnArbitraryDomainProxy() {
        Map<String, Object> plan = new HashMap<>(Map.of("credentialId", 7L, "credentialRevision", 3L, "credentialKind", "apiKeyRestricted", "actorId", 42L));
        ApiRequest external = new ApiRequest(null, null); external.setType("service"); external.setMethod("PUT"); external.setId("10");
        external.setRequestObject(Map.of("ai.resource.change.domain.descriptor", true));
        assertEquals("OwnerPermissionDenied", denied(() -> guard.authorizeBoundDomain(plan, external)));
    }
    @Test public void boundStackApprovalRechecksNewChildrenAndCurrentChildOwnership() {
        Map<String, Object> plan = Map.of("credentialId", 7L, "credentialRevision", 3L, "credentialKind", "apiKeyRestricted", "actorId", 42L);
        ApiRequest domain = new ApiRequest(null, request.getSchemaFactory());
        domain.setType("stack"); domain.setMethod("POST"); domain.setId("3"); domain.setAction("activateservices");
        domain.setAttribute("ai.resource.change.domain.descriptor",
                io.cattle.platform.iaas.api.auth.change.AiResourceChangeDescriptor.require("stack", "start"));
        var child = ApiKeyPolicyEvaluator.Target.stackResource("service", "1s11", "1a77", "1st3");
        when(guard.targets.resolve(domain, owner)).thenReturn(List.of(ApiKeyPolicyEvaluator.Target.stackResource("stack", "1st3", "1a77", "1st3")));
        policy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("stack", "1st3"), Set.of("update")),
                        new ApiKeyPolicy.Rule("child", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("service", "1s11"), Set.of("update"))));
        when(guard.targets.stackLifecycleTargets(domain, owner)).thenReturn(List.of());
        guard.authorizeBoundDomain(plan, domain);
        when(guard.targets.stackLifecycleTargets(domain, owner)).thenReturn(List.of(child));
        assertEquals("KeyPolicyDenied", denied(() -> guard.authorizeBoundDomain(plan, domain)));
        when(guard.targets.stackLifecycleTargets(domain, owner)).thenThrow(new ClientVisibleException(403, "OwnerPermissionDenied"));
        assertEquals("OwnerPermissionDenied", denied(() -> guard.authorizeBoundDomain(plan, domain)));
    }
    @Test public void dispatchCapturesDomainRequestAndRestoresAfterFailure() {
        request.setAttribute("apiKey.audit.decision", "ALLOW"); request.setAttribute("apiKey.audit.requestId", "request-1");
        ApiRequest domain = new ApiRequest(request); domain.setType("service"); domain.setId("10"); domain.setMethod("POST"); domain.setAction("upgrade");
        assertThrows(IllegalStateException.class, () -> AiKeyAuthorization.dispatch(request, domain, () -> {
            assertSame(domain, ApiContext.getContext().getApiRequest());
            assertEquals(7L, ApiKeyCredentialContext.get(domain).credentialId());
            assertEquals("upgrade", domain.getAttribute("apiKey.audit.operation"));
            assertEquals("ALLOW", domain.getAttribute("apiKey.audit.decision"));
            Map<String,Object> captured = new ApiKeyProcessAuthorization().capture(mock(io.cattle.platform.engine.process.LaunchConfiguration.class));
            assertEquals("service", captured.get("targetType")); assertEquals("10", captured.get("targetId"));
            assertEquals("upgrade", captured.get("operation")); assertEquals(3L, captured.get("policyRevision"));
            throw new IllegalStateException("fixture");
        }));
        assertSame(request, ApiContext.getContext().getApiRequest());
    }
}
