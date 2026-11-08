package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.PortTable;
import io.cattle.platform.core.model.tables.ServiceExposeMapTable;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.PortRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.After;
import org.junit.Test;

public class ApiKeyPortScopeTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void registeredPortUsesOnlyItsPersistedSourceAncestryAndNeverAnInputParentGrant() {
        Fixture f = new Fixture(); var target = f.resolver.resolveObject("port", f.port, f.owner);
        assertEquals("port", target.resourceType()); assertEquals("1p9", target.resourceId());
        assertEquals("1a5", target.projectId()); assertEquals("1st8", target.stackId());
        PortRecord spoof = new PortRecord(); spoof.setId(9L); spoof.setAccountId(5L); spoof.setInstanceId(404L);
        assertThrows(ClientVisibleException.class, () -> f.resolver.resolveObject("port", spoof, f.owner));
        f.port.setRemoved(new Date(1)); assertThrows(ClientVisibleException.class, () -> f.resolver.resolveObject("port", f.port, f.owner));
        f.port.setRemoved(null); f.source.setAccountId(6L);
        assertThrows(ClientVisibleException.class, () -> f.resolver.resolveObject("port", f.port, f.owner));
        assertEquals(ApiKeyPolicyEvaluator.TargetLevel.UNRESOLVED, f.resolver.resolveObject("futurePortPlugin", f.port, f.owner).level());
    }

    @Test public void portQueryUsesLiveSourceProjectStackAndExplicitDenyBeforePagination() {
        var allow = new ApiKeyPolicy.Rule("source-stack-update", ApiKeyPolicy.Effect.ALLOW,
                ApiKeyPolicy.Scope.stack("1st8"), Set.of("update"));
        var deny = new ApiKeyPolicy.Rule("exact-port-deny", ApiKeyPolicy.Effect.DENY,
                ApiKeyPolicy.Scope.resource("port", "1p9"), Set.of("update"));
        var key = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(allow, deny));
        var condition = ApiKeyQueryScopes.condition(key, "port", PortTable.PORT, "update", (type, id) ->
                Long.parseLong(id.replaceFirst("^1[a-z]+", "")));
        String sql = DSL.using(SQLDialect.MYSQL).renderInlined(DSL.selectFrom(PortTable.PORT).where(condition).limit(1)).toLowerCase();
        for (String part : List.of("`port`.`instance_id`", "key_scope_link_instance", "key_scope_link_project", "key_scope_link_stack", "and not"))
            assertTrue(part + ": " + sql, sql.contains(part));
        assertTrue(sql.indexOf("where") < sql.lastIndexOf("limit"));
        var evaluator = new ApiKeyPolicyEvaluator(); Fixture f = new Fixture(); var target = f.resolver.resolveObject("port", f.port, f.owner);
        assertFalse(evaluator.evaluate(key, new ApiKeyPolicyEvaluator.Request(true, "update", true, List.of(target)), java.time.Instant.now()).allowed());
        var readOnly = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null,
                List.of(new ApiKeyPolicy.Rule("deny-write", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.global(), Set.of("update"))));
        assertFalse(evaluator.evaluate(readOnly, new ApiKeyPolicyEvaluator.Request(true, "update", true, List.of(target)), java.time.Instant.now()).allowed());
    }

    @Test public void exactPortUpdateAndReadDoNotGrantTheSourceContainerAndClosedExpiredStillFail() {
        Fixture f = new Fixture(); ApiKeyAuthorizationService service = new ApiKeyAuthorizationService();
        service.targets = f.resolver; service.auditSinks = List.of(mock(io.cattle.platform.api.auth.ApiKeyAuditSink.class));
        service.clock = java.time.Clock.systemUTC();
        ApiRequest request = new ApiRequest(null, f.factory); request.setType("port"); request.setId("1p9"); request.setMethod("PUT");
        request.setRequestObject(Map.of("publicPort", 8080)); request.setSchemaFactory(f.factory);
        var portGrant = new ApiKeyPolicy.Rule("only-port", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("port", "1p9"), Set.of("update", "read"));
        var key = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(portGrant));
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1L, 10L, "apiKey", 1, key));
        service.authorize(request, f.owner); assertEquals("ALLOW", request.getAttribute("apiKey.audit.decision"));
        request.setType("container"); request.setId("1i3"); request.setMethod("GET"); request.setRequestObject(null);
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, () -> service.authorize(request, f.owner)).getCode());
        request.setType("port"); request.setId("1p9");
        for (ApiKeyPolicy blocked : List.of(new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of()),
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, java.time.Instant.EPOCH, List.of()))) {
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1L, 10L, "apiKey", 1, blocked));
            assertThrows(ClientVisibleException.class, () -> service.authorize(request, f.owner));
        }
    }

    static class Fixture {
        final ApiKeyTargetResolver resolver = new ApiKeyTargetResolver();
        final AccountRecord project = new AccountRecord(); final InstanceRecord source = new InstanceRecord();
        final StackRecord stack = new StackRecord(); final PortRecord port = new PortRecord(); final Policy owner;
        final SchemaFactory factory = mock(SchemaFactory.class);
        Fixture() {
            project.setId(5L); project.setKind("project"); project.setState("active"); project.setUuid("adminProject");
            AccountRecord principal = new AccountRecord(); principal.setId(10L); principal.setKind("user");
            owner = new AccountPolicy(project, principal, Set.of(), new NoPolicyOptions());
            source.setId(3L); source.setAccountId(5L); source.setStackId(8L); source.setState("running");
            stack.setId(8L); stack.setAccountId(5L); stack.setState("active");
            port.setId(9L); port.setAccountId(5L); port.setInstanceId(3L); port.setState("active");
            resolver.objectManager = mock(ObjectManager.class); resolver.projectAccess = mock(ProjectAccessService.class);
            when(resolver.projectAccess.checkAccess(same(project), any(Policy.class))).thenReturn(project);
            when(resolver.objectManager.loadResource(Account.class, 5L)).thenReturn(project);
            when(resolver.objectManager.loadResource(Instance.class, 3L)).thenReturn(source);
            when(resolver.objectManager.loadResource(Stack.class, 8L)).thenReturn(stack);
            when(resolver.objectManager.loadResource(Port.class, 9L)).thenReturn(port);
            when(resolver.objectManager.loadResource("port", 9L)).thenReturn(port);
            when(resolver.objectManager.loadResource("container", 3L)).thenReturn(source);
            when(resolver.objectManager.find(ServiceExposeMap.class, ServiceExposeMapTable.SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                    ServiceExposeMapTable.SERVICE_EXPOSE_MAP.REMOVED, null, ServiceExposeMapTable.SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of());
            when(resolver.objectManager.find(ServiceExposeMap.class, ServiceExposeMapTable.SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                    ServiceExposeMapTable.SERVICE_EXPOSE_MAP.REMOVED, null, ServiceExposeMapTable.SERVICE_EXPOSE_MAP.MANAGED, false,
                    ServiceExposeMapTable.SERVICE_EXPOSE_MAP.UPGRADE, true)).thenReturn(List.of());
            SchemaImpl portSchema = new SchemaImpl(); portSchema.setId("port"); portSchema.setResourceMethods(List.of("GET", "PUT"));
            FieldImpl publicPort = new FieldImpl(); publicPort.setType("int"); publicPort.setUpdate(true);
            portSchema.getResourceFields().put("publicPort", publicPort);
            when(factory.getSchema("port")).thenReturn(portSchema);
            SchemaImpl containerSchema = new SchemaImpl(); containerSchema.setId("container"); containerSchema.setResourceMethods(List.of("GET"));
            when(factory.getSchema("container")).thenReturn(containerSchema);
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(factory);
            formatter.setTypeMappings(Map.of("project", "a", "port", "p", "container", "i", "stack", "st")); resolver.idFormatter = formatter;
            ApiContext.newContext().setIdFormatter(formatter);
        }
    }
}
