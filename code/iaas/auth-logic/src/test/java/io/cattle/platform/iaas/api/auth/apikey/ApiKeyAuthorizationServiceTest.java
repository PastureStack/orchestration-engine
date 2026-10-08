package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import io.cattle.platform.core.model.tables.InstanceTable;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

public class ApiKeyAuthorizationServiceTest {
    ApiKeyAuthorizationService service;
    ApiKeyAuditSink sink;
    ApiKeyTargetResolver targets;
    Policy owner;
    ApiRequest request;
    Schema schema;
    Instant now = Instant.parse("2026-10-08T00:00:00Z");

    @Before public void setup() {
        ApiContext.newContext();
        owner = mock(Policy.class);
        ApiContext.getContext().setPolicy(owner);
        SchemaFactory factory = mock(SchemaFactory.class);
        schema = mock(Schema.class);
        when(factory.getSchema("container")).thenReturn(schema);
        when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT", "DELETE"));
        when(schema.getCollectionMethods()).thenReturn(List.of("GET", "POST"));
        request = new ApiRequest(null, factory);
        request.setType("container"); request.setId("1i1"); request.setMethod("PUT");
        ApiContext.getContext().setApiRequest(request);
        service = new ApiKeyAuthorizationService();
        targets = mock(ApiKeyTargetResolver.class); service.targets = targets;
        sink = mock(ApiKeyAuditSink.class); service.auditSinks = List.of(sink);
        service.resourceManagers = mock(ResourceManagerLocator.class);
        service.objectManager = mock(ObjectManager.class);
        service.clock = Clock.fixed(now, ZoneOffset.UTC);
        when(targets.resolve(request, owner)).thenReturn(List.of(target("1i1", "1st1")));
        when(targets.references(request, owner)).thenReturn(List.of());
    }
    @After public void cleanup() { ApiContext.remove(); }

    private ApiKeyPolicyEvaluator.Target target(String id, String stack) {
        return ApiKeyPolicyEvaluator.Target.stackResource("container", id, "1a5", stack);
    }
    private ApiKeyPolicy custom(String op) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("stack1", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st1"), Set.of(op))));
    }
    private void use(ApiKeyPolicy policy) { ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 5, "apiKey", 1, policy)); }
    private ClientVisibleException denied(String reason) {
        ClientVisibleException error = assertThrows(ClientVisibleException.class, () -> service.authorize(request, owner));
        assertEquals(reason, error.getCode());
        verify(sink).recordDecision(request, owner);
        assertEquals("DENY", request.getAttribute("apiKey.audit.decision"));
        return error;
    }

    @Test public void fullHasNoNewRegistryOrResolverRestriction() {
        use(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        request.setAction("futurePluginAction");
        service.authorize(request, owner);
        verifyNoInteractions(targets);
        assertNull(service.constrain(request, request.getSchemaFactory(), "instance", InstanceTable.INSTANCE));
        verify(sink).recordDecision(request, owner);
    }
    @Test public void legacyHasExactlyExistingPrincipalAndNoNewConstraint() {
        use(null); service.authorize(request, owner);
        verifyNoInteractions(targets);
        assertNull(service.constrain(request, request.getSchemaFactory(), "instance", InstanceTable.INSTANCE));
    }
    @Test public void nonKeyRequestIsUntouched() {
        service.authorize(request, owner); verifyNoInteractions(sink, targets);
    }
    @Test public void closedDeniesBeforeResourceAccessButIsAudited() {
        use(new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of()));
        denied("KeyPolicyDenied"); verifyNoInteractions(targets);
    }
    @Test public void fullWithExplicitExpiryStillExpiresAtBoundary() {
        use(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, now, List.of()));
        denied("ApiKeyExpired"); verifyNoInteractions(targets);
    }
    @Test public void permittedUpdateUsesPersistedScopeAndCurrentOwner() {
        use(custom("update")); service.authorize(request, owner);
        assertEquals("ALLOW", request.getAttribute("apiKey.audit.decision"));
        verify(targets).resolve(request, owner); verify(sink).recordDecision(request, owner);
    }
    @Test public void cannotUseOwnerForbiddenMethodEvenWithGlobalGrant() {
        when(schema.getResourceMethods()).thenReturn(List.of("GET"));
        use(custom("update")); denied("OwnerPermissionDenied"); verifyNoInteractions(targets);
    }
    @Test public void crossStackDestinationCannotHideBehindAllowedSource() {
        use(custom("update")); when(targets.resolve(request, owner)).thenReturn(List.of(target("1i1", "1st1"), target("1i2", "1st2")));
        denied("KeyScopeDenied");
    }
    @Test public void nestedReferencedResourceMustBeReadableToo() {
        use(custom("update")); when(targets.references(request, owner)).thenReturn(List.of(target("1i9", "1st2")));
        denied("KeyScopeDenied");
    }
    @Test public void customUnknownActionFailsClosed() {
        request.setAction("futurePluginAction");
        when(schema.getResourceActions()).thenReturn(java.util.Map.of("futurePluginAction", mock(io.github.ibuildthecloud.gdapi.model.Action.class)));
        use(custom("update")); denied("UnknownOperation");
    }
    @Test public void restrictedKeyCannotMintUnrestrictedToken() {
        request.setType("token"); request.setId(null); request.setMethod("POST"); use(custom("create"));
        denied("ApiKeyRestrictedDelegationUnsupported");
    }
    @Test public void missingAuditOrDurabilityFailureStopsAdmission() {
        use(null); service.auditSinks = List.of();
        assertEquals("AuditUnavailable", assertThrows(ClientVisibleException.class, () -> service.authorize(request, owner)).getCode());
        service.auditSinks = List.of(sink); doThrow(new IllegalStateException("sensitive database detail")).when(sink).recordDecision(request, owner);
        assertEquals("AuditUnavailable", assertThrows(ClientVisibleException.class, () -> service.authorize(request, owner)).getCode());
    }
    @Test public void syntheticCollectionCannotLeakScopedRows() {
        request.setMethod("GET"); request.setId(null); use(custom("read"));
        when(service.resourceManagers.getResourceManagerByType("container")).thenReturn(mock(AbstractNoOpResourceManager.class));
        denied("KeyScopeDenied");
    }
    @Test public void collectionOutputGuardUsesExactSetAndDoesNotTouchFull() {
        Object permitted = new Object(), forbidden = new Object();
        use(custom("read"));
        when(service.objectManager.getType(permitted)).thenReturn("container");
        when(service.objectManager.getType(forbidden)).thenReturn("container");
        when(targets.resolveObject("container", permitted, owner)).thenReturn(target("1i1", "1st1"));
        when(targets.resolveObject("container", forbidden, owner)).thenReturn(target("1i2", "1st2"));
        assertEquals(List.of(permitted), service.filterCollection(request, List.of(permitted, forbidden)));
        use(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        List<?> originals = List.of(permitted, forbidden);
        assertSame(originals, service.filterCollection(request, originals));
    }
}
