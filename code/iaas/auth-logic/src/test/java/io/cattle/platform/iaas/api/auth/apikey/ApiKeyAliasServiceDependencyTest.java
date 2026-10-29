package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.*;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.*;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.*;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.*;
import org.junit.After;
import org.junit.Test;

public class ApiKeyAliasServiceDependencyTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void exactDnsActionCapturesTheRealMapWithoutAnyInstanceOrFabricatedServiceFrame() throws Exception {
        Fixture f = new Fixture(); assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
        ProcessRecord job = f.linkJob();
        assertTrue(ProcessAuthorization.executionBound(job)); assertNull(ProcessAuthorization.verifiedChild(job));
        assertEquals("map-uuid", ProcessAuthorization.metadata(job).get("aliasMapUuid"));
        f.hook.beforeExecution(job, ProcessAuthorization.metadata(job));
        var dependency = f.resolver.resolveAliasServiceDependency(f.alias, f.map, f.owner, f.schemas, job, ProcessAuthorization.metadata(job));
        assertEquals("1st8", dependency.lifecycle().scope().stackId()); assertEquals("1st9", dependency.reference().stackId());
        assertNull(f.resolver.resolveAliasServiceDependency(f.alias, new InstanceRecord(), f.owner, f.schemas, job, ProcessAuthorization.metadata(job)));
        assertEquals(new ApiKeyOperations.Operation("update", true), ApiKeyOperations.of("POST", "setservicelinks", null, "dnsService"));
        for (String type : List.of("loadBalancerService", "container", "stack", "pluginService"))
            assertFalse(ApiKeyOperations.of("POST", "setservicelinks", null, type).registered());
    }

    @Test public void queuedMapWorkRechecksTheLiveKeyRoleExactUuidsParentsAndExplicitDeny() throws Exception {
        for (String invalid : List.of("revoked", "revision", "read-role", "read-deny", "map-deny", "owner", "parent", "uuid", "target-uuid")) {
            Fixture f = new Fixture(); ProcessRecord job = f.linkJob();
            switch (invalid) {
            case "revoked" -> f.key.setState("inactive");
            case "revision" -> f.key.setData(new ApiKeyPolicyCodec().store(f.key, f.policy, 2));
            case "read-role" -> when(f.targetSchema.getResourceMethods()).thenReturn(List.of());
            case "read-deny", "map-deny" -> {
                var scope = ApiKeyPolicy.Scope.resource(invalid.equals("read-deny") ? "service" : "serviceConsumeMap",
                        invalid.equals("read-deny") ? "1s9" : "1scm7");
                List<ApiKeyPolicy.Rule> rules = new ArrayList<>(f.policy.getRules());
                rules.add(new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, scope,
                        Set.of(invalid.equals("read-deny") ? "read" : "update")));
                f.key.setData(new ApiKeyPolicyCodec().store(f.key, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM,
                        ApiKeyPolicy.Effect.DENY, null, rules), 1));
            }
            case "owner" -> f.map.setAccountId(99L);
            case "parent" -> f.alias.setStackId(9L);
            case "uuid" -> f.map.setUuid("replaced-map");
            case "target-uuid" -> f.target.setUuid("replaced-target");
            default -> { }
            }
            assertThrows(invalid, ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(job, ProcessAuthorization.metadata(job)));
        }
    }

    @Test public void actionInputSchemaPreflightsCrossIdReadAndRejectsRemoteOrAdvancedLinksBeforeAnyOwnerMutation() {
        Fixture f = new Fixture(); f.authorize();
        assertEquals("ALLOW", f.request.getAttribute("apiKey.audit.decision"));
        f.target.setAccountId(99L);
        assertThrows(ClientVisibleException.class, f::authorize); f.target.setAccountId(5L);
        when(f.targetSchema.getResourceMethods()).thenReturn(List.of()); assertThrows(ClientVisibleException.class, f::authorize);
        when(f.targetSchema.getResourceMethods()).thenReturn(List.of("GET"));
        for (Map<String, Object> link : List.of(Map.<String, Object>of("service", "https://PRIVATE", "name", "remote"),
                Map.<String, Object>of("serviceId", "1s9", "ports", List.of("80:80")), Map.<String, Object>of("serviceId", "1s9", "name", "alias"))) {
            f.request.setRequestObject(Map.of("serviceLinks", List.of(link))); assertThrows(ClientVisibleException.class, f::authorize);
        }
        // FULL retains the original native API and does not inherit this new CUSTOM-Key subset.
        ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(1, 10, "apiKeyRestricted", 1,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of())));
        f.authorization.authorize(f.request, f.owner); assertEquals("ALLOW", f.request.getAttribute("apiKey.audit.decision"));
    }

    @Test public void sameProjectReadableKindStillRequiresTheOwnersLiveObjectGrant() {
        Fixture f = new Fixture();
        when(f.owner.authorizeObject(f.target)).thenReturn(null);
        ClientVisibleException failure = assertThrows(ClientVisibleException.class, f::authorize);
        assertEquals("OwnerPermissionDenied", failure.getCode());
        assertEquals("DENY", f.request.getAttribute("apiKey.audit.decision"));
        verify(f.owner, atLeastOnce()).authorizeObject(f.target);
    }

    @Test public void aliasReferenceCannotBypassTheCommonCollectorWhenItsWritableSchemaIsAbsent() {
        for (String invalid : List.of("absent", "wrong-type", "not-writable")) {
            Fixture f = new Fixture();
            Schema link = f.schemas.getSchema("serviceLink");
            FieldImpl reference = new FieldImpl();
            reference.setType("wrong-type".equals(invalid) ? "reference[container]" : "reference[service]");
            reference.setCreate(!"not-writable".equals(invalid));
            when(link.getResourceFields()).thenReturn("absent".equals(invalid) ? Map.of() : Map.of("serviceId", reference));
            ClientVisibleException failure = assertThrows(ClientVisibleException.class, f::authorize);
            assertEquals("OwnerPermissionDenied", failure.getCode());
            assertEquals("DENY", f.request.getAttribute("apiKey.audit.decision"));
            verify(f.objects, never()).loadResource(eq(Service.class), eq(9L));
        }
    }

    @Test public void validatedNativeInputSchemaRemainsTheExactCollectorSnapshot() {
        Fixture f = new Fixture();
        Schema selected = f.schemas.getSchema("setServiceLinksInput");
        Schema replaced = mock(Schema.class);
        when(replaced.getResourceFields()).thenReturn(Map.of());
        when(f.schemas.getSchema("setServiceLinksInput")).thenReturn(selected, replaced);
        var references = f.resolver.references(f.request, f.owner);
        assertEquals(1, references.size());
        verify(f.owner, atLeastOnce()).authorizeObject(f.target);
    }

    @Test public void baseServiceRouteCannotAvoidPersistedAliasReferenceSubset() {
        for (String invalid : List.of("name", "remote", "ports", "account", "kind-read", "object-read")) {
            Fixture f = new Fixture(); f.baseServiceRoute();
            switch (invalid) {
            case "name" -> f.request.setRequestObject(Map.of("serviceLinks", List.of(Map.of("serviceId", "1s9", "name", "named"))));
            case "remote" -> f.request.setRequestObject(Map.of("serviceLinks", List.of(Map.of("service", "remote/service"))));
            case "ports" -> f.request.setRequestObject(Map.of("serviceLinks", List.of(Map.of("serviceId", "1s9", "ports", List.of("80:80")))));
            case "account" -> f.target.setAccountId(99L);
            case "kind-read" -> when(f.targetSchema.getResourceMethods()).thenReturn(List.of());
            case "object-read" -> when(f.owner.authorizeObject(f.target)).thenReturn(null);
            default -> { }
            }
            assertThrows(invalid, ClientVisibleException.class, f::authorize);
            assertEquals("DENY", f.request.getAttribute("apiKey.audit.decision"));
        }
    }

    @Test public void baseServiceRouteSimpleLinksCaptureAllEightExactAliasPins() throws Exception {
        Fixture f = new Fixture(); f.baseServiceRoute(); ProcessRecord job = f.linkJob();
        Map<String, Object> metadata = ProcessAuthorization.metadata(job);
        assertEquals("service", f.request.getType()); assertEquals("dnsService", metadata.get("targetType"));
        assertEquals(Map.of("aliasServiceUuid", "alias-uuid", "aliasMapUuid", "map-uuid", "aliasConsumedServiceId", "9",
                "aliasConsumedServiceUuid", "target-uuid", "aliasStackId", "8", "aliasStackUuid", "stack-8",
                "aliasConsumedStackId", "9", "aliasConsumedStackUuid", "stack-9"),
                f.resolver.aliasSchedulingReferences(job, metadata, f.owner));
        assertTrue(ProcessAuthorization.executionBound(job)); assertNull(ProcessAuthorization.verifiedChild(job));
        for (var entry : f.resolver.aliasSchedulingReferences(job, metadata, f.owner).entrySet())
            assertEquals(entry.getValue(), metadata.get(entry.getKey()));
        f.hook.beforeExecution(job, metadata);
        f.target.setUuid("reassigned-target");
        assertThrows(ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(job, metadata));
    }

    @Test public void baseServiceRouteFullAdvancedAndCookieCallsKeepNativeAuthority() throws Exception {
        Fixture f = new Fixture(); f.baseServiceRoute();
        var full = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of());
        f.key.setData(new ApiKeyPolicyCodec().store(f.key, full, 1));
        ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(1, 10, "apiKeyRestricted", 1, full));
        f.request.setRequestObject(Map.of("serviceLinks", List.of(Map.of("serviceId", "1s9", "name", "native-name"))));
        f.map.setName("native-name"); ProcessRecord job = f.linkJob();
        assertEquals("service", ProcessAuthorization.metadata(job).get("targetType"));
        assertFalse(ProcessAuthorization.metadata(job).containsKey("aliasMapUuid"));
        f.hook.beforeExecution(job, ProcessAuthorization.metadata(job));
        ApiRequest cookie = new ApiRequest(null, f.schemas); cookie.setType("service"); cookie.setId("2");
        cookie.setMethod("POST"); cookie.setAction("setservicelinks"); cookie.setRequestObject(f.request.getRequestObject());
        ApiContext.getContext().setApiRequest(cookie);
        f.authorization.authorize(cookie, f.owner);
        assertTrue(f.hook.capture(new LaunchConfiguration("serviceconsumemap.create", "serviceConsumeMap", "7", 5L, 0, Map.of())).isEmpty());
    }

    @Test public void ordinaryServiceBaseRouteDoesNotAcquireTheAliasSubsetOrProof() throws Exception {
        Fixture f = new Fixture(); f.baseServiceRoute(); f.alias.setKind("service");
        f.request.setRequestObject(Map.of("serviceLinks", List.of(Map.of("serviceId", "1s9", "name", "ordinary-name"))));
        ProcessRecord job = f.linkJob();
        assertEquals("ALLOW", f.request.getAttribute("apiKey.audit.decision"));
        assertEquals("service", ProcessAuthorization.metadata(job).get("targetType"));
        assertFalse(ProcessAuthorization.metadata(job).containsKey("aliasMapUuid"));
    }

    @Test public void removalBindsOnlyInsideTheRealServiceFrameAndDetachedPayloadsCannotAddReferences() throws Exception {
        Fixture f = new Fixture(); f.map.setState("active"); f.request.setMethod("DELETE"); f.request.setAction(null);
        f.request.setRequestObject(Map.of()); f.authorize();
        LaunchConfiguration root = new LaunchConfiguration("service.remove", "service", "2", 5L, 0, Map.of());
        ProcessAuthorization.prepare(root, List.of(f.hook)); Map<String, Object> metadata = ProcessAuthorization.metadata(root);
        var engine = EngineContext.getEngineContext(); engine.pushAuthorization(metadata);
        var frame = engine.pushVerifiedExecution("service", "2", metadata);
        ProcessRecord child;
        try {
            var launch = new LaunchConfiguration("serviceconsumemap.remove", "serviceConsumeMap", "7", 5L, 0, Map.of());
            ProcessAuthorization.prepare(launch, List.of()); child = new ProcessRecord(launch, 101L, "server");
            f.hook.beforeExecution(child, ProcessAuthorization.metadata(child));
            assertFalse(ProcessAuthorization.verifiedChild(child).references().isEmpty());
        } finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
        child.setData(f.json.readValue(f.json.writeValueAsString(child.getData())));
        f.alias.setRemoved(new Date(1)); f.alias.setState("removed"); f.map.setRemoved(new Date(1)); f.map.setState("removed");
        f.hook.beforeExecution(child, ProcessAuthorization.metadata(child));
        f.map.setUuid("replacement");
        assertThrows(ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(child, ProcessAuthorization.metadata(child)));
        var fake = new LaunchConfiguration("serviceconsumemap.create", "serviceConsumeMap", "7", 5L, 0,
                Map.of(ProcessAuthorization.DATA_KEY, metadata, ProcessAuthorization.CAPTURED_EXECUTION_KEY, Map.of("authorization", metadata)));
        ProcessAuthorization.prepare(fake, List.of()); assertTrue(ProcessAuthorization.metadata(fake).isEmpty());
        assertFalse(ProcessAuthorization.executionBound(new ProcessRecord(fake, 103L, "server")));
    }

    private static class Fixture {
        final ObjectManager objects = mock(ObjectManager.class); final Policy owner = mock(Policy.class);
        final ApiKeyTargetResolver resolver = new ApiKeyTargetResolver(); final ApiKeyProcessAuthorization hook = new ApiKeyProcessAuthorization();
        final ApiKeyAuthorizationService authorization = new ApiKeyAuthorizationService();
        final SchemaFactory schemas = mock(SchemaFactory.class); final Schema targetSchema = mock(Schema.class), aliasSchema = mock(Schema.class);
        final ServiceRecord alias = new ServiceRecord(), target = new ServiceRecord(); final ServiceConsumeMapRecord map = new ServiceConsumeMapRecord();
        final CredentialRecord key = new CredentialRecord(); final ApiRequest request = new ApiRequest(null, schemas);
        final JacksonJsonMapper json = new JacksonJsonMapper(); final ApiKeyPolicy policy;
        Fixture() {
            resolver.objectManager = objects; resolver.projectAccess = mock(ProjectAccessService.class);
            when(owner.getAccountId()).thenReturn(5L); when(owner.getAuthenticatedAsAccountId()).thenReturn(10L);
            when(owner.authorizeObject(any())).thenAnswer(call -> call.getArgument(0));
            when(schemas.getBaseType(anyString())).thenAnswer(call -> "dnsService".equals(call.getArgument(0)) ? "service" : call.getArgument(0));
            var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("service", "s", "serviceConsumeMap", "scm", "stack", "st", "project", "a", "credential", "c"));
            resolver.idFormatter = formatter;
            alias.setId(2L); alias.setUuid("alias-uuid"); alias.setKind("dnsService"); alias.setState("active"); alias.setAccountId(5L); alias.setStackId(8L);
            target.setId(9L);
            target.setUuid("target-uuid"); target.setKind("service"); target.setState("active"); target.setAccountId(5L); target.setStackId(9L);
            map.setId(7L); map.setUuid("map-uuid"); map.setAccountId(5L); map.setServiceId(2L); map.setConsumedServiceId(9L); map.setState("requested");
            AccountRecord project = new AccountRecord(); project.setId(5L); project.setKind("project"); project.setState("active"); project.setUuid("project");
            when(objects.loadResource(Account.class, 5L)).thenReturn(project); when(resolver.projectAccess.checkAccess(project, owner)).thenReturn(project);
            for (long id : List.of(8L, 9L)) {
                StackRecord stack = new StackRecord(); stack.setId(id); stack.setUuid("stack-" + id); stack.setAccountId(5L); stack.setState("active");
                when(objects.loadResource(Stack.class, id)).thenReturn(stack);
            }
            when(objects.loadResource(Service.class, 2L)).thenReturn(alias); when(objects.loadResource(Service.class, 9L)).thenReturn(target);
            when(objects.loadResource("dnsService", 2L)).thenReturn(alias); when(objects.loadResource("service", "9")).thenReturn(target);
            when(objects.loadResource(ServiceConsumeMap.class, 7L)).thenReturn(map); when(objects.loadResource("serviceConsumeMap", "7")).thenReturn(map);
            when(schemas.getSchema("dnsService")).thenReturn(aliasSchema); when(schemas.getSchema("service")).thenReturn(targetSchema);
            when(targetSchema.getResourceMethods()).thenReturn(List.of("GET"));
            when(aliasSchema.getResourceMethods()).thenReturn(List.of("GET", "PUT", "DELETE"));
            Action action = mock(Action.class); when(action.getInput()).thenReturn("setServiceLinksInput");
            when(aliasSchema.getResourceActions()).thenReturn(Map.of("setservicelinks", action));
            Schema input = mock(Schema.class), link = mock(Schema.class); when(schemas.getSchema("setServiceLinksInput")).thenReturn(input);
            when(schemas.getSchema("serviceLink")).thenReturn(link);
            FieldImpl links = new FieldImpl(); links.setType("array[serviceLink]"); links.setCreate(true);
            FieldImpl reference = new FieldImpl(); reference.setType("reference[service]"); reference.setCreate(true);
            when(input.getResourceFields()).thenReturn(Map.of("serviceLinks", links));
            when(link.getResourceFields()).thenReturn(Map.of("serviceId", reference));
            policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                    new ApiKeyPolicy.Rule("alias", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), Set.of("read", "create", "update", "delete")),
                    new ApiKeyPolicy.Rule("target", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st9"), Set.of("read"))));
            key.setId(1L);
            key.setAccountId(10L); key.setKind("apiKeyRestricted"); key.setState("active"); key.setData(new ApiKeyPolicyCodec().store(key, policy, 1));
            when(objects.loadResource(Credential.class, 1L)).thenReturn(key);
            hook.objectManager = objects; hook.targets = resolver; hook.authenticator = mock(ApiAuthenticator.class);
            when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, schemas));
            authorization.targets = resolver; authorization.objectManager = objects; authorization.auditSinks = List.of(mock(ApiKeyAuditSink.class));
            request.setType("dnsService"); request.setId("2"); request.setMethod("POST"); request.setAction("setservicelinks");
            request.setRequestObject(Map.of("serviceLinks", List.of(Map.of("serviceId", "1s9"))));
            ApiContext.newContext().setApiRequest(request); ApiContext.getContext().setPolicy(owner); ApiContext.getContext().setIdFormatter(formatter);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 10, "apiKeyRestricted", 1, policy));
            request.setAttribute("apiKey.audit.keyId", "1c1");
        }
        void authorize() { authorization.authorize(request, owner); }
        void baseServiceRoute() {
            request.setType("service");
            when(objects.loadResource("service", 2L)).thenReturn(alias);
            var actions = aliasSchema.getResourceActions();
            when(targetSchema.getResourceActions()).thenReturn(actions);
        }
        ProcessRecord linkJob() throws Exception {
            authorize(); var config = new LaunchConfiguration("serviceconsumemap.create", "serviceConsumeMap", "7", 5L, 0, Map.of());
            ProcessAuthorization.prepare(config, List.of(hook)); var record = new ProcessRecord(config, 100L, "server");
            record.setData(json.readValue(json.writeValueAsString(record.getData()))); return record;
        }
    }
}
