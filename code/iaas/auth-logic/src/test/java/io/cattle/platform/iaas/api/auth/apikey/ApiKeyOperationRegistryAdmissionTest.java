package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.Service;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import io.cattle.platform.core.model.tables.records.*;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.ResourceManagerRequestHandler;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Admission with the real 348 ancestry resolver, followed by the real dispatcher.
 * The mocked business manager proves the denied path never invokes a handler;
 * this does not claim asynchronous completion or native all-role acceptance. */
@RunWith(Parameterized.class)
public class ApiKeyOperationRegistryAdmissionTest {
    @Parameterized.Parameters(name = "{0}.{1} -> {2}")
    public static Object[][] cases() {
        return new Object[][] {
            {"stack", "addoutputs", "update"}, {"stack", "create", "create"}, {"stack", "error", "update"},
            {"service", "continueupgrade", "upgrade"}, {"service", "create", "create"},
            {"container", "create", "create"}, {"container", "allocate", "update"},
            {"container", "deallocate", "update"}, {"container", "error", "update"},
            {"container", "updatehealthy", "update"}, {"container", "updatereinitializing", "update"},
            {"container", "updateunhealthy", "update"}, {"volume", "create", "create"},
            {"volume", "allocate", "update"}, {"volume", "deallocate", "update"},
            {"environment", "addoutputs", "update"}, {"instance", "allocate", "update"}
            , {"stack", "activateservices", "update"}, {"stack", "deactivateservices", "update"}
            , {"environment", "activateservices", "update"}, {"environment", "deactivateservices", "update"}
        };
    }
    private final String type, action, operation;
    private ApiKeyAuthorizationService guard;
    private ApiKeyTargetResolver resolver;
    private ApiKeyAuditSink audit;
    private Policy owner;
    private ApiRequest request;
    private ResourceManager manager;
    private ResourceManagerRequestHandler dispatcher;
    private Object resource;
    private Schema schema;
    private String canonical, targetId;

    public ApiKeyOperationRegistryAdmissionTest(String type, String action, String operation) {
        this.type = type; this.action = action; this.operation = operation;
    }
    @Before public void setup() {
        ApiContext.newContext(); canonical = ApiKeyQueryScopes.canonical(type);
        owner = mock(Policy.class); when(owner.getAccountId()).thenReturn(5L);
        when(owner.authorizeObject(any())).thenAnswer(i -> i.getArgument(0));
        ApiContext.getContext().setPolicy(owner);
        schema = mock(Schema.class);
        when(schema.getResourceActions()).thenReturn(Map.of(action, mock(Action.class)));
        SchemaFactory factory = mock(SchemaFactory.class); when(factory.getSchema(type)).thenReturn(schema);
        request = new ApiRequest(null, factory); request.setType(type); request.setId("10");
        request.setMethod("POST"); request.setAction(action); request.setRequestObject(Map.of());
        ApiContext.getContext().setApiRequest(request);
        StackRecord stack = new StackRecord(); stack.setId(20L); stack.setAccountId(5L);
        switch (canonical) {
            case "stack" -> { StackRecord value = new StackRecord(); value.setId(10L); value.setAccountId(5L); resource = value; }
            case "service" -> { ServiceRecord value = new ServiceRecord(); value.setId(10L); value.setAccountId(5L); value.setStackId(20L); resource = value; }
            case "container" -> { InstanceRecord value = new InstanceRecord(); value.setId(10L); value.setAccountId(5L); value.setStackId(20L); resource = value; }
            case "volume" -> { VolumeRecord value = new VolumeRecord(); value.setId(10L); value.setAccountId(5L); value.setStackId(20L); resource = value; }
            default -> throw new AssertionError(type);
        }
        AccountRecord project = new AccountRecord(); project.setId(5L); project.setKind("project");
        ObjectManager objects = mock(ObjectManager.class);
        when(objects.loadResource(type, 10L)).thenReturn(resource);
        when(objects.loadResource(Account.class, 5L)).thenReturn(project);
        when(objects.loadResource(Stack.class, 20L)).thenReturn(stack);
        if (resource instanceof Stack root) when(objects.loadResource(Stack.class, 10L)).thenReturn(root);
        resolver = new ApiKeyTargetResolver(); resolver.objectManager = objects;
        resolver.idFormatter = mock(IdFormatter.class);
        when(resolver.idFormatter.parseId(anyString())).thenAnswer(i -> i.getArgument(0));
        when(resolver.idFormatter.formatId(anyString(), any())).thenAnswer(i -> "1" +
                Map.of("project", "a", "stack", "st", "service", "s", "container", "i", "volume", "v").get(ApiKeyQueryScopes.canonical(i.getArgument(0))) + i.getArgument(1));
        targetId = "1" + Map.of("stack", "st", "service", "s", "container", "i", "volume", "v").get(canonical) + "10";
        audit = mock(ApiKeyAuditSink.class);
        guard = new ApiKeyAuthorizationService(); guard.targets = resolver;
        guard.objectManager = objects; guard.auditSinks = List.of(audit);
        manager = mock(ResourceManager.class); when(manager.resourceAction(type, request)).thenReturn(resource);
        ResourceManagerLocator locator = mock(ResourceManagerLocator.class); when(locator.getResourceManager(request)).thenReturn(manager);
        dispatcher = new ResourceManagerRequestHandler(); dispatcher.setResourceManagerLocator(locator);
    }
    @After public void cleanup() { ApiContext.remove(); }
    private ApiKeyPolicy.Rule rule(String id, ApiKeyPolicy.Effect effect, String resourceId, String op) {
        return new ApiKeyPolicy.Rule(id, effect, ApiKeyPolicy.Scope.resource(canonical, resourceId), Set.of(op));
    }
    private void custom(ApiKeyPolicy.Rule... rules) {
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 5, "apiKey", 1,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(rules))));
    }
    private void dispatch() throws java.io.IOException { guard.authorize(request, owner); dispatcher.handle(request); }
    private void decision(String expected) {
        assertEquals(expected, request.getAttribute("apiKey.audit.decision"));
        UUID.fromString((String) request.getAttribute("apiKey.audit.requestId"));
        verify(audit).recordDecision(request, owner);
    }
    private void denied(String code) {
        assertEquals(code, assertThrows(ClientVisibleException.class, this::dispatch).getCode());
        verifyNoInteractions(manager); decision("DENY");
    }
    @Test public void exactGrantDispatchesOnlyThePersistedTarget() throws Exception {
        custom(rule("allow", ApiKeyPolicy.Effect.ALLOW, targetId, operation)); dispatch(); decision("ALLOW");
        assertEquals(operation, request.getAttribute("apiKey.audit.operation"));
        verify(resolver.objectManager).loadResource(type, 10L); verify(manager).resourceAction(type, request);
    }
    @Test public void explicitDenyStopsHandler() {
        custom(rule("allow", ApiKeyPolicy.Effect.ALLOW, targetId, operation), rule("deny", ApiKeyPolicy.Effect.DENY, targetId, operation)); denied("KeyPolicyDenied");
    }
    @Test public void anotherIdCannotBorrowTheSameParent() {
        custom(rule("other", ApiKeyPolicy.Effect.ALLOW, targetId + "9", operation)); denied("KeyScopeDenied");
    }
    @Test public void readGrantDoesNotAuthorizeMutation() {
        custom(rule("read", ApiKeyPolicy.Effect.ALLOW, targetId, "read")); denied("KeyScopeDenied");
    }
    @Test public void liveOwnerRevocationStopsHandler() {
        custom(rule("allow", ApiKeyPolicy.Effect.ALLOW, targetId, operation)); when(owner.authorizeObject(resource)).thenReturn(null); denied("OwnerPermissionDenied");
    }
    @Test public void fullAndLegacyDoNotAcquireTheTypedRegistryRestriction() throws Exception {
        request.setAction("futurePluginAction");
        for (ApiKeyPolicy policy : new ApiKeyPolicy[] {null, new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of())}) {
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 5, "apiKey", 1, policy));
            guard.authorize(request, owner);
        }
        verifyNoInteractions(resolver.objectManager); verify(audit, times(2)).recordDecision(request, owner);
    }
    @Test public void unsupportedRootsAndSensitiveOrMultiTargetActionsStayUnknown() {
        assertFalse(ApiKeyOperations.of("POST", action, null, "pluginResource").registered());
        assertFalse(ApiKeyOperations.of("POST", action, null).registered());
        for (String unsupported : List.of("setmembers", "changesecret", "activateservices", "deactivateservices", "setservicelinks", "replay", "backup", "console", "proxy", "portpreflight", "volumepreflight")) {
            if ("stack".equals(canonical) && Set.of("activateservices", "deactivateservices").contains(unsupported))
                assertEquals(new ApiKeyOperations.Operation("update", true), ApiKeyOperations.of("POST", unsupported, null, type));
            else assertFalse(ApiKeyOperations.of("POST", unsupported, null, type).registered());
        }
        for (String other : List.of("pluginResource", "composeProject", "kubernetesStack"))
            for (String batch : List.of("activateservices", "deactivateservices"))
                assertFalse(ApiKeyOperations.of("POST", batch, null, other).registered());
        request.setAction("futurePluginAction");
        when(schema.getResourceActions()).thenReturn(Map.of("futurePluginAction", mock(Action.class)));
        custom(rule("allow", ApiKeyPolicy.Effect.ALLOW, targetId, operation)); denied("UnknownOperation");
    }

    @Test public void stackBatchChecksEveryChildBeforeDispatchButInheritsTheAuthorizedRootGrant() throws Exception {
        if (!"stack".equals(canonical) || !Set.of("activateservices", "deactivateservices").contains(action)) return;
        ServiceRecord child = new ServiceRecord(); child.setId(21L); child.setStackId(10L); child.setAccountId(5L);
        when(resolver.objectManager.find(Service.class, SERVICE.STACK_ID, 10L, SERVICE.REMOVED, null)).thenReturn(List.of(child));
        ApiKeyPolicy.Rule allow = rule("root", ApiKeyPolicy.Effect.ALLOW, targetId, "update");
        ApiKeyPolicy.Rule denyChild = new ApiKeyPolicy.Rule("child", ApiKeyPolicy.Effect.DENY,
                ApiKeyPolicy.Scope.resource("service", "1s21"), Set.of("update"));
        custom(allow, denyChild); denied("KeyPolicyDenied");
        clearInvocations(audit, manager); custom(allow); dispatch(); decision("ALLOW");
        verify(manager).resourceAction(type, request);
    }

    @Test public void stackBatchRejectsForeignRelationsAndRevokedChildOwnersBeforeAnyEffects() {
        if (!"stack".equals(canonical) || !Set.of("activateservices", "deactivateservices").contains(action)) return;
        ServiceRecord child = new ServiceRecord(); child.setId(21L); child.setStackId(10L); child.setAccountId(88L);
        when(resolver.objectManager.find(Service.class, SERVICE.STACK_ID, 10L, SERVICE.REMOVED, null)).thenReturn(List.of(child));
        custom(rule("root", ApiKeyPolicy.Effect.ALLOW, targetId, "update")); denied("KeyScopeDenied");
        child.setAccountId(5L); when(owner.authorizeObject(child)).thenReturn(null);
        clearInvocations(audit); denied("OwnerPermissionDenied");
    }
}
