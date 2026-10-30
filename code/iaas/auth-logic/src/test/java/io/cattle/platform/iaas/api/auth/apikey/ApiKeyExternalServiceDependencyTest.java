package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyExternalServiceDependencyTest {
    ApiKeyTargetResolver resolver;
    ServiceRecord service;
    ServiceExposeMapRecord map;
    StackRecord stack;
    Policy owner;
    Map<String, Object> binding;

    @Before public void setup() {
        resolver = new ApiKeyTargetResolver(); resolver.objectManager = mock(ObjectManager.class);
        resolver.projectAccess = mock(ProjectAccessService.class); owner = mock(Policy.class);
        when(owner.authorizeObject(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(owner.getAccountId()).thenReturn(5L);
        SchemaFactory schemas = mock(SchemaFactory.class); when(schemas.getBaseType(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0).equals("externalService") ? "service" : invocation.getArgument(0));
        DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
        formatter.setTypeMappings(Map.of("stack", "st", "project", "a", "service", "s"));
        resolver.idFormatter = formatter;
        service = new ServiceRecord(); service.setId(2L); service.setUuid("service-two"); service.setKind("externalService");
        service.setState("active"); service.setAccountId(5L); service.setStackId(8L);
        map = new ServiceExposeMapRecord(); map.setId(3L); map.setUuid("map-three"); map.setAccountId(5L); map.setServiceId(2L);
        map.setManaged(true); map.setUpgrade(false); map.setHostName("db.test"); map.setState("active");
        stack = new StackRecord(); stack.setId(8L); stack.setAccountId(5L); stack.setState("active");
        var project = new AccountRecord(); project.setId(5L); project.setKind("project"); project.setState("active");
        when(resolver.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        when(resolver.objectManager.loadResource(ServiceExposeMap.class, 3L)).thenReturn(map);
        when(resolver.objectManager.loadResource(Stack.class, 8L)).thenReturn(stack);
        when(resolver.objectManager.loadResource(Account.class, 5L)).thenReturn(project);
        when(resolver.projectAccess.checkAccess(project, owner)).thenReturn(project);
        binding = new LinkedHashMap<>(); binding.put("targetType", "externalService"); binding.put("targetId", "2");
        binding.put("requestMethod", "POST"); binding.put("requestAction", "activate"); binding.put("operation", "update");
        binding.put("requestCollection", false); binding.put("preview", false);
    }

    @Test public void realFormatterAndNativeManagedEndpointMapPreserveStackScopeAndExplicitDeny() {
        var dependency = resolve(job("serviceexposemap.create"));
        assertEquals("1st8", dependency.scope().stackId()); assertEquals("1a5", dependency.scope().projectId());
        var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update")),
                new ApiKeyPolicy.Rule("deny-map", ApiKeyPolicy.Effect.DENY,
                        ApiKeyPolicy.Scope.resource("serviceExposeMap", dependency.scope().resourceId()), Set.of("update"))));
        var decision = new ApiKeyPolicyEvaluator().evaluateAuthorizedDependency(policy,
                new ApiKeyPolicyEvaluator.Request(true, "update", true, dependency.resources()), Instant.now());
        assertFalse(decision.allowed()); assertEquals("KeyPolicyDenied", decision.reason().getCode());
    }

    @Test public void sameOwnerDeleteMayFinishAfterRootRemovalButUpdateCannotBorrowThatTombstone() {
        service.setRemoved(new Date(1)); service.setState("removed");
        binding.put("requestMethod", "DELETE"); binding.put("operation", "delete"); binding.remove("requestAction");
        assertNotNull(resolve(job("serviceexposemap.remove")));
        binding.put("requestMethod", "POST"); binding.put("operation", "update"); binding.put("requestAction", "deactivate");
        assertThrows(ClientVisibleException.class, () -> resolve(job("serviceexposemap.remove")));
    }

    @Test public void missingProofCrossParentScopeInstancesUnsafeEndpointsAndOwnerRevocationFailClosed() {
        for (String invalid : List.of("proof", "service", "owner", "stack", "instance", "unmanaged", "upgrade", "endpoint", "role")) {
            setup(); ProcessRecord job = job("serviceexposemap.create");
            switch (invalid) {
            case "proof" -> job.getData().remove(ProcessAuthorization.VERIFIED_CHILD_KEY);
            case "service" -> map.setServiceId(9L);
            case "owner" -> map.setAccountId(9L);
            case "stack" -> stack.setAccountId(9L);
            case "instance" -> map.setInstanceId(9L);
            case "unmanaged" -> map.setManaged(false);
            case "upgrade" -> map.setUpgrade(true);
            case "endpoint" -> map.setHostName("https://PRIVATE@db.test");
            case "role" -> when(owner.authorizeObject(map)).thenReturn(null);
            default -> { }
            }
            assertThrows(invalid, ClientVisibleException.class, () -> resolve(job));
        }
    }

    @Test public void actualQueuedHookRechecksTheLiveKeyRoleRevisionAndExplicitEndpointDeny() {
        var hook = new ApiKeyProcessAuthorization(); hook.objectManager = resolver.objectManager; hook.targets = resolver;
        hook.authenticator = mock(io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.class);
        var schemas = mock(SchemaFactory.class); var schema = mock(io.github.ibuildthecloud.gdapi.model.Schema.class);
        when(schemas.getSchema("externalService")).thenReturn(schema);
        when(schema.getResourceActions()).thenReturn(Map.of("activate", mock(io.github.ibuildthecloud.gdapi.model.Action.class),
                "deactivate", mock(io.github.ibuildthecloud.gdapi.model.Action.class)));
        when(schema.getResourceMethods()).thenReturn(List.of("PUT", "DELETE"));
        when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(
                new io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.CurrentAuthorization(owner, schemas));
        var formatter = new DefaultIdFormatter();
        SchemaFactory aliases = mock(SchemaFactory.class); when(aliases.getBaseType(anyString())).thenAnswer(call ->
                "externalService".equals(call.getArgument(0)) ? "service" : call.getArgument(0));
        formatter.setSchemaFactory(aliases);
        formatter.setTypeMappings(Map.of("credential", "c", "service", "s", "serviceExposeMap", "sem", "stack", "st", "project", "a"));
        resolver.idFormatter = formatter;
        var key = new io.cattle.platform.core.model.tables.records.CredentialRecord();
        key.setId(1L); key.setAccountId(10L); key.setKind("apiKeyRestricted"); key.setState("active");
        var allow = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                new ApiKeyPolicy.Rule("stack", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("update", "delete"))));
        key.setData(new ApiKeyPolicyCodec().store(key, allow, 1));
        when(hook.objectManager.loadResource(io.cattle.platform.core.model.Credential.class, 1L)).thenReturn(key);
        when(hook.objectManager.loadResource("externalService", 2L)).thenReturn(service);
        when(hook.objectManager.loadResource("serviceExposeMap", "3")).thenReturn(map);
        binding.putAll(Map.of("keyId", "1c1", "policyRevision", 1L, "principalAccountId", 10L, "accountId", 5L,
                "requestId", "aichange-11111111-1111-1111-1111-111111111111"));
        ProcessRecord start = job("serviceexposemap.create"); hook.beforeExecution(start, binding);
        binding.put("requestAction", "deactivate"); ProcessRecord stop = job("serviceexposemap.remove");
        map.setRemoved(new Date(1)); map.setState("removed"); hook.beforeExecution(stop, binding);
        for (String denial : List.of("revoked", "revision", "role", "deny-map", "cross-owner")) {
            key.setState("active"); key.setData(new ApiKeyPolicyCodec().store(key, allow, 1));
            when(owner.authorizeObject(map)).thenReturn(map); map.setAccountId(5L);
            switch (denial) {
            case "revoked" -> key.setState("inactive");
            case "revision" -> key.setData(new ApiKeyPolicyCodec().store(key, allow, 2));
            case "role" -> when(owner.authorizeObject(map)).thenReturn(null);
            case "cross-owner" -> map.setAccountId(99L);
            case "deny-map" -> key.setData(new ApiKeyPolicyCodec().store(key,
                    new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                            List.of(allow.getRules().get(0), new ApiKeyPolicy.Rule("map", ApiKeyPolicy.Effect.DENY,
                                    ApiKeyPolicy.Scope.resource("serviceExposeMap", "1sem3"), Set.of("update")))), 1));
            default -> { }
            }
            assertThrows(denial, io.cattle.platform.engine.process.ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(stop, binding));
        }
    }

    private ApiKeyTargetResolver.LifecycleDependency resolve(ProcessRecord job) {
        return resolver.resolveExternalServiceDependency(service, map, owner, job, binding);
    }

    private ProcessRecord job(String process) {
        var engine = EngineContext.getEngineContext(); engine.pushAuthorization(binding);
        var parent = engine.pushVerifiedExecution("service", "2", binding);
        var launch = new LaunchConfiguration(process, "serviceExposeMap", "3", 5L, 0, Map.of());
        try { ProcessAuthorization.prepare(launch, List.of()); }
        finally { engine.popVerifiedExecution(parent); engine.popAuthorization(); }
        return new ProcessRecord(launch, 99L, "server");
    }
}
