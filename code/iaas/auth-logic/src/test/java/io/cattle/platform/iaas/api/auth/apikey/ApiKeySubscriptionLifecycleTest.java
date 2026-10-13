package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.ApiKeySubscriptionAuditEvent;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.pubsub.subscribe.SubscriptionCloseReason;
import io.cattle.platform.eventing.model.EventVO;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiKeySubscriptionLifecycleTest {
    @Before public void setup() { ApiContext.newContext().setIdFormatter(new IdentityFormatter()); }
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void fullAndLegacyRemainUnrestrictedWhileObservingRealClose() {
        for (boolean legacy : List.of(false, true)) {
            var f = new ApiKeyDelegationServiceTest.Fixture();
            var full = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of());
            f.store(full, 3);
            ApiRequest request = subscription(f);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(12, 42, "apiKey", legacy ? 0 : 3, legacy ? null : full));
            List<ApiKeySubscriptionAuditEvent> events = new ArrayList<>();
            var authorization = authorization(f, events);
            var lifecycle = authorization.captureLifecycle(request);
            assertNotNull(lifecycle); assertNull(authorization.capture(request)); assertEquals(0, f.authorizations);
            ApiContext.remove(); // Callback thread needs neither the request nor its authentication token.
            lifecycle.closed(SubscriptionCloseReason.CLIENT_CLOSED);
            lifecycle.closed(SubscriptionCloseReason.INTERNAL_ERROR);
            assertEquals(1, events.size());
            var event = events.get(0);
            assertEquals("CLIENT_CLOSED", event.closeReason()); assertNull(event.authorizationFailureCode());
            assertEquals(42, event.principalAccountId()); assertEquals(7, event.accountId());
            assertEquals("12", event.keyId()); assertTrue(event.eventId().matches("[a-f0-9]{64}"));
            assertFalse(event.toString().contains(f.key.getSecretValue()));
            assertFalse(event.toString().contains("request-private-token"));
            ApiContext.newContext().setIdFormatter(new IdentityFormatter());
        }
    }

    @Test public void liveRevokeRevisionOwnerAndExpiryKeepTheirActualFailureCodes() {
        for (String code : List.of("ApiKeyRevoked", "ApiKeyPolicyChanged", "OwnerPermissionDenied", "ApiKeyExpired")) {
            var f = new ApiKeyDelegationServiceTest.Fixture();
            ApiRequest request = subscription(f);
            List<ApiKeySubscriptionAuditEvent> events = new ArrayList<>();
            var authorization = authorization(f, events);
            var lifecycle = authorization.captureLifecycle(request);
            var session = authorization.capture(request);
            switch (code) {
                case "ApiKeyRevoked" -> f.key.setState("inactive");
                case "ApiKeyPolicyChanged" -> f.store(f.policy, 4);
                case "OwnerPermissionDenied" -> f.ownerAllowed = false;
                case "ApiKeyExpired" -> f.service.clock = Clock.fixed(f.now.plusSeconds(90), ZoneOffset.UTC);
                default -> throw new AssertionError();
            }
            EventVO<Object> ping = new EventVO<>(); ping.setName("ping");
            ApiKeyDelegationServiceTest.assertCode(code, () -> session.currentPolicy(ping));
            lifecycle.closed(SubscriptionCloseReason.AUTHORIZATION_FAILED);
            assertEquals(code, events.get(0).authorizationFailureCode());
            assertEquals(3, events.get(0).policyRevision());
        }
    }

    @Test public void filteredResourceDenialIsNotAStreamAuthorizationFailure() {
        var f = new ApiKeyDelegationServiceTest.Fixture();
        f.store(ApiKeyDelegationServiceTest.Fixture.custom(Set.of("read")), 3);
        ApiRequest request = subscription(f);
        List<ApiKeySubscriptionAuditEvent> events = new ArrayList<>();
        var authorization = authorization(f, events);
        var lifecycle = authorization.captureLifecycle(request);
        var session = authorization.capture(request);
        f.store(ApiKeyDelegationServiceTest.Fixture.custom(Set.of("exec")), 3);
        EventVO<Object> row = new EventVO<>(); row.setName("resource.change"); row.setResourceType("container"); row.setResourceId("9");
        assertNull(session.currentPolicy(row)); assertTrue(events.isEmpty());
        lifecycle.closed(SubscriptionCloseReason.CLIENT_CLOSED);
        assertNull(events.get(0).authorizationFailureCode());
    }

    @Test public void failedPersistenceRetriesSameImmutableFirstTerminal() {
        var f = new ApiKeyDelegationServiceTest.Fixture();
        var authorization = new ApiKeySubscriptionAuthorization(); authorization.delegations = f.service;
        AtomicInteger attempts = new AtomicInteger(); List<ApiKeySubscriptionAuditEvent> events = new ArrayList<>();
        authorization.auditSinks = List.of(new ApiKeyAuditSink() {
            @Override public void recordDecision(ApiRequest request, Policy policy) { throw new AssertionError(); }
            @Override public void recordSubscriptionTerminal(ApiKeySubscriptionAuditEvent event) {
                events.add(event); if (attempts.getAndIncrement() == 0) throw new IllegalStateException("unavailable");
            }
        });
        var lifecycle = authorization.captureLifecycle(subscription(f));
        assertThrows(IllegalStateException.class, () -> lifecycle.closed(SubscriptionCloseReason.IO_ERROR));
        lifecycle.closed(SubscriptionCloseReason.CLIENT_CLOSED); lifecycle.closed(SubscriptionCloseReason.SERVER_CLOSED);
        assertEquals(2, attempts.get()); assertSame(events.get(0), events.get(1));
        assertEquals("IO_ERROR", events.get(1).closeReason());
    }

    @Test public void invalidTraceCannotCreateNewFullOrLegacyPermissionGate() {
        var f = new ApiKeyDelegationServiceTest.Fixture();
        var full = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()); f.store(full, 3);
        var authorization = authorization(f, new ArrayList<>());
        ApiRequest request = subscription(f);
        request.setAttribute("apiKey.audit.requestId", "client-controlled-private-token");
        assertNull(authorization.captureLifecycle(request)); assertNull(authorization.capture(request)); assertEquals(0, f.authorizations);
        assertNull(authorization.captureLifecycle(new ApiRequest(null, null)));
    }

    private static ApiKeySubscriptionAuthorization authorization(ApiKeyDelegationServiceTest.Fixture f, List<ApiKeySubscriptionAuditEvent> events) {
        var authorization = new ApiKeySubscriptionAuthorization(); authorization.delegations = f.service;
        authorization.auditSinks = List.of(new ApiKeyAuditSink() {
            @Override public void recordDecision(ApiRequest request, Policy policy) { throw new AssertionError(); }
            @Override public void recordSubscriptionTerminal(ApiKeySubscriptionAuditEvent event) { events.add(event); }
        });
        return authorization;
    }

    private static ApiRequest subscription(ApiKeyDelegationServiceTest.Fixture f) {
        ApiRequest request = f.request(); request.setType("subscribe"); request.setId(null); request.setAction(null); request.setMethod("GET");
        request.setAttribute("apiKey.audit.requestId", "550e8400-e29b-41d4-a716-446655440000");
        request.setAttribute("private-test-token", "request-private-token");
        return request;
    }
}
