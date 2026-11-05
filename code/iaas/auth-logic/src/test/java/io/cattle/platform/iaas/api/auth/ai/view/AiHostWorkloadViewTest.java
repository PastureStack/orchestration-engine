package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.core.dao.HostDao;
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.InstanceHostMapRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

public class AiHostWorkloadViewTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void sameNameRunningAndStoppedChildrenUseFreshContainerAndStackOwnersWithoutDtoAugmentation() {
        Fixture f = new Fixture(); f.candidates.add(mapping(1, 11)); f.candidates.add(mapping(2, 12));
        f.resources.put("container:11", resource("11", "container", "running", Map.of("stackId", "31")));
        f.resources.put("container:12", resource("12", "container", "stopped", Map.of("stackId", "32")));
        f.resources.put("stack:31", resource("31", "stack", "active", Map.of("name", "Blue")));
        f.resources.put("stack:32", resource("32", "stack", "active", Map.of("name", "Green")));
        List<?> refs = refs(f.read()); assertEquals(2, refs.size());
        assertEquals("Blue", ((Map<?, ?>) refs.get(0)).get("stackName"));
        assertEquals("Green", ((Map<?, ?>) refs.get(1)).get("stackName"));
        assertEquals("stopped", ((Map<?, ?>) refs.get(1)).get("state"));
        verify(f.service.keyAuthorization, times(5)).actor(any());
        verify(f.manager, never()).convertResponse(any(Object.class), any());
        assertFalse(refs.toString().contains("PRIVATE")); assertSame(f.originalContext, ApiContext.getContext().getApiRequest());
    }

    @Test public void boundUnknownSnapshotsExcludeMovedForeignRemovedAndUnreadableChildrenOrStackNames() {
        Fixture f = new Fixture();
        for (int id = 11; id <= 15; id++) f.candidates.add(mapping(id, id));
        f.resources.put("container:11", resource("11", "container", "stopped", Map.of("stackId", "31")));
        f.resources.put("container:12", resource("12", "container", "running", Map.of("accountId", "88", "name", "PRIVATE_FOREIGN")));
        f.resources.put("container:13", resource("13", "container", "removed", Map.of("removed", "yesterday", "name", "PRIVATE_REMOVED")));
        f.resources.put("container:14", resource("14", "container", "running", Map.of("name", "PRIVATE_DENIED")));
        f.resources.put("container:15", resource("15", "container", "running", Map.of("name", "PRIVATE_MOVED")));
        f.nativeScope = (type, id) -> !"14".equals(id); when(f.hosts.isWorkloadCurrent(77, 7, 15, 15, null)).thenReturn(false);
        Map<?, ?> result = f.read(); assertEquals(1, refs(result).size()); assertFalse(result.toString().contains("PRIVATE"));
        assertEquals("unknown", ((Map<?, ?>) refs(result).getFirst()).get("stackResolution"));
        assertEquals("unknown", result.get("workloadReferencesStatus")); assertFalse(result.containsKey("hiddenCount"));
        f.resources.put("stack:31", resource("31", "stack", "active", Map.of("name", "PRIVATE_OLD_STACK")));
        when(f.hosts.isWorkloadCurrent(77, 7, 11, 11, 31L)).thenReturn(false);
        Map<?, ?> moved = f.read(); assertEquals(1, refs(moved).size());
        assertEquals("unknown", ((Map<?, ?>) refs(moved).getFirst()).get("stackResolution"));
        assertFalse(moved.toString().contains("PRIVATE_OLD_STACK"));

        f.candidates.clear();
        for (int id = 1; id <= 51; id++) { f.candidates.add(mapping(id, 100 + id));
            f.resources.put("container:" + (100 + id), resource("" + (100 + id), "container", "stopped", Map.of())); }
        assertEquals(50, refs(f.read()).size()); verify(f.manager, never()).getById(eq("container"), eq("151"), any());
        f.candidates.clear(); assertTrue(refs(f.read()).isEmpty());
        f.service.read(f.request(Map.of("resourceType", "host"))); verify(f.hosts, times(4)).getWorkloadCandidates(77, 7);
    }

    @Test public void realScopedKeyNativePredicateHidesChildrenAndTheirAuditIdentitiesButDirectTargetStillDenies() throws Exception {
        Fixture f = new Fixture(); f.candidates.add(mapping(1, 11)); f.candidates.add(mapping(2, 12));
        f.resources.put("container:11", resource("11", "container", "stopped", Map.of()));
        f.resources.put("container:12", resource("12", "container", "running", Map.of("name", "PRIVATE_DENIED")));
        ApiKeyAuthorizationService access = new ApiKeyAuthorizationService(); ApiKeyTargetResolver targets = mock(ApiKeyTargetResolver.class);
        ApiKeyAuditSink sink = mock(ApiKeyAuditSink.class); List<String> decisions = new ArrayList<>();
        doAnswer(call -> { ApiRequest r = call.getArgument(0); assertEquals("GET", r.getMethod());
            decisions.add(r.getType() + ":" + r.getId() + ":" + r.getAttribute("apiKey.audit.decision")); return null;
        }).when(sink).recordDecision(any(), any());
        field(access, "targets", targets); field(access, "auditSinks", List.of(sink));
        when(targets.resolve(any(), any())).thenAnswer(call -> { ApiRequest r = call.getArgument(0);
            return List.of(ApiKeyPolicyEvaluator.Target.projectResource(r.getType(), r.getId(), "77")); });
        when(targets.stackLifecycleTargets(any(), any())).thenReturn(List.of()); when(targets.references(any(), any())).thenReturn(List.of());
        when(targets.parseScopeId(anyString(), anyString())).thenAnswer(call -> Long.valueOf(call.<String>getArgument(1)));
        f.service.keyAccess = access;
        ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                new ApiKeyPolicy.Rule("host", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("host", "7"), Set.of("read")),
                new ApiKeyPolicy.Rule("container", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("container", "11"), Set.of("read"))));
        ApiKeyCredentialContext key = new ApiKeyCredentialContext(5, 42, "apiKey", 3, policy);
        try (var c = java.sql.DriverManager.getConnection("jdbc:h2:mem:" + java.util.UUID.randomUUID())) {
            var sql = org.jooq.impl.DSL.using(c, org.jooq.SQLDialect.H2,
                    new org.jooq.conf.Settings().withRenderQuotedNames(org.jooq.conf.RenderQuotedNames.NEVER));
            sql.execute("create schema cattle");
            sql.execute("create table cattle.instance(id bigint,account_id bigint,environment_id bigint,service_id bigint)");
            sql.execute("create table cattle.service(id bigint,account_id bigint,environment_id bigint,removed timestamp)");
            sql.execute("create table cattle.service_expose_map(id bigint,account_id bigint,instance_id bigint,service_id bigint,"
                    + "managed boolean,upgrade boolean,removed timestamp)");
            sql.execute("insert into cattle.instance values(11,77,null,null),(12,77,null,null)");
            f.nativeScope = (type, id) -> { if (!"container".equals(type)) return true;
                var table = io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
                var condition = access.constrain(ApiContext.getContext().getApiRequest(), f.schemas, "instance", table);
                return sql.fetchExists(sql.selectOne().from(table).where(table.ID.eq(Long.valueOf(id))).and(condition)); };
            ApiRequest request = f.request(Map.of("resourceType", "host", "resourceId", "7")); ApiKeyCredentialContext.attach(request, key);
            Map<?, ?> result = (Map<?, ?>) ((List<?>) f.service.read(request).get("data")).getFirst();
            assertEquals(1, refs(result).size()); assertFalse(result.toString().contains("PRIVATE"));
            assertEquals(List.of("host:7:ALLOW"), decisions);
            ApiRequest direct = new ApiRequest(null, f.schemas); direct.setType("container"); direct.setId("12"); direct.setMethod("GET");
            ApiKeyCredentialContext.attach(direct, key); assertThrows(ClientVisibleException.class, () -> access.authorize(direct, f.live));
            assertEquals(List.of("host:7:ALLOW", "container:12:DENY"), decisions);
        }
    }

    @Test public void parentRbacAndKeyRevocationFailClosedBeforeRelationshipOutputAndRestoreContext() {
        Fixture denied = new Fixture(); when(denied.schema.getResourceMethods()).thenReturn(List.of());
        assertThrows(ClientVisibleException.class, denied::read); verifyNoInteractions(denied.hosts);
        Fixture f = new Fixture(); f.candidates.add(mapping(1, 11)); f.candidates.add(mapping(2, 12));
        f.resources.put("container:11", resource("11", "container", "stopped", Map.of()));
        when(f.service.keyAuthorization.actor(any())).thenReturn(f.owner, f.owner).thenThrow(new ClientVisibleException(403, "ApiKeyPolicyChanged"));
        assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class, f::read).getCode());
        verify(f.manager, never()).getById(eq("container"), eq("12"), any());
        assertSame(f.originalContext, ApiContext.getContext().getApiRequest()); assertSame(f.owner, ApiContext.getContext().getPolicy());
        Fixture unattested = new Fixture(); unattested.candidates.add(mapping(1, 11));
        when(unattested.manager.supportsScopedCollectionQuery(any())).thenReturn(false);
        ApiRequest request = unattested.request(Map.of("resourceType", "host", "resourceId", "7"));
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKey", 3,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null, List.of())));
        assertTrue(refs((Map<?, ?>) ((List<?>) unattested.service.read(request).get("data")).getFirst()).isEmpty());
        verify(unattested.manager, never()).getById(eq("container"), anyString(), any());
    }

    @Test public void cookieFullAndLegacyHostMetadataMatchesActualNativeWrappingAndHostsFilter() throws Exception {
        for (int mode = 0; mode < 3; mode++) {
            Fixture f = new Fixture(); HostRecord host = (HostRecord) f.nativeRows.get("host:7");
            host.setAgentState("disconnected"); host.setMemory(4096L); host.setMilliCpu(2000L); host.setLocalStorageMb(8192L);
            host.setData(Map.of("fields", Map.of("hostname", "native.example", "agentIpAddress", "192.0.2.1", "dockerVersion", "29.0",
                    "memTotal", 4096L, "cpuCount", 2L, "instanceIds", List.of("PRIVATE"), "agentToken", "PRIVATE")));
            for (String name : List.of("hostname", "agentIpAddress", "dockerVersion", "memTotal", "cpuCount",
                    "agentState", "memory", "milliCpu", "localStorageMb")) {
                var capability = mock(io.github.ibuildthecloud.gdapi.model.Field.class);
                when(capability.isIncludeInList()).thenReturn(true);
                when(capability.getTypeEnum()).thenReturn(io.github.ibuildthecloud.gdapi.model.FieldType.STRING);
                when(capability.getValue(any())).thenAnswer(call -> {
                    if (io.cattle.platform.object.util.DataUtils.getFields(host).containsKey(name))
                        return io.cattle.platform.object.util.DataUtils.getFields(host).get(name);
                    return host.getClass().getMethod("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1)).invoke(host);
                }); f.fields.put(name, capability);
            }
            var fullSchema = new io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl(); fullSchema.setId("host"); fullSchema.setResourceFields(f.fields);
            var nativeResource = new io.github.ibuildthecloud.gdapi.model.impl.WrappedResource(ApiContext.getContext().getIdFormatter(), f.schemas,
                    fullSchema, host, new LinkedHashMap<>(io.cattle.platform.object.util.DataUtils.getFields(host)), Set.of(), "GET", false);
            var filter = new io.cattle.platform.iaas.api.filter.hosts.HostsFilter(); field(filter, "hostDao", f.hosts);
            when(f.hosts.getInstancesPerHost(anyList(), any())).thenReturn(Map.of(7L, List.of("PRIVATE_INSTANCE_ID")));
            when(f.hosts.getPhysicalHostsForHosts(anyList())).thenReturn(new java.util.HashMap<>());
            ApiRequest nativeRequest = f.request(Map.of()); nativeRequest.setResponseObject(host);
            Resource filtered = filter.filter(nativeRequest, host, nativeResource);
            Map<String, Object> expected = AiResourceViewService.project("host", filtered.getId(), filtered.getFields());
            ApiRequest request = f.request(Map.of("resourceType", "host", "resourceId", "7"));
            if (mode != 0) ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKey", 3,
                    mode == 1 ? new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()) : null));
            Map<String, Object> actual = new LinkedHashMap<>((Map<String, Object>) ((List<?>) f.service.read(request).get("data")).getFirst());
            actual.remove("workloadReferences"); actual.remove("workloadReferencesStatus"); assertEquals(expected, actual);
            assertEquals("disconnected", actual.get("state")); assertEquals(4096L, actual.get("memory"));
            assertFalse(actual.toString().contains("PRIVATE")); verify(f.manager, never()).convertResponse(any(Object.class), any());
            var v1Resource = new io.github.ibuildthecloud.gdapi.model.impl.WrappedResource(ApiContext.getContext().getIdFormatter(), f.schemas,
                    fullSchema, host, new LinkedHashMap<>(io.cattle.platform.object.util.DataUtils.getFields(host)), Set.of(), "GET", false);
            nativeRequest.setVersion("v1");
            Map<String, Object> v1Expected = AiResourceViewService.project("host", v1Resource.getId(),
                    filter.filter(nativeRequest, host, v1Resource).getFields());
            request.setVersion("v1");
            actual = new LinkedHashMap<>((Map<String, Object>) ((List<?>) f.service.read(request).get("data")).getFirst());
            actual.remove("workloadReferences"); actual.remove("workloadReferencesStatus"); assertEquals(v1Expected, actual);
            assertEquals("active", actual.get("state"));
            var hostname = f.fields.get("hostname"); clearInvocations(hostname); when(hostname.getTransform()).thenReturn("sensitive-transform");
            Map<?, ?> row = (Map<?, ?>) ((List<?>) f.service.read(request).get("data")).getFirst(); assertFalse(row.containsKey("hostname"));
            verify(hostname, never()).getValue(any());
        }
    }

    private static void field(Object owner, String name, Object value) throws Exception {
        var field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
    private static List<?> refs(Map<?, ?> row) { return (List<?>) row.get("workloadReferences"); }
    private static InstanceHostMap mapping(long id, long instance) { InstanceHostMapRecord m = new InstanceHostMapRecord();
        m.setId(id); m.setHostId(7L); m.setInstanceId(instance); return m; }
    private static Resource resource(String id, String type, String state, Map<String, Object> extra) {
        Map<String, Object> fields = new LinkedHashMap<>(Map.of("accountId", "77", "name", "same", "state", state, "launchConfig", "PRIVATE"));
        fields.putAll(extra); return new ResourceImpl(id, type, fields);
    }
    static class Fixture extends AiVolumeUsageViewTest.Fixture {
        final HostDao hosts = mock(HostDao.class);
        final List<InstanceHostMap> candidates = new ArrayList<>();
        Fixture() {
            service.hosts = hosts; when(hosts.getWorkloadCandidates(77, 7)).thenAnswer(call -> candidates);
            when(hosts.isWorkloadCurrent(anyLong(), anyLong(), anyLong(), anyLong(), any())).thenReturn(true);
            HostRecord host = new HostRecord(); host.setId(7L); host.setAccountId(77L); host.setName("named-host"); host.setState("active");
            nativeRows.put("host:7", host);
        }
        @Override Map<?, ?> read() {
            return (Map<?, ?>) ((List<?>) service.read(request(Map.of("resourceType", "host", "resourceId", "7"))).get("data")).getFirst();
        }
    }
}
