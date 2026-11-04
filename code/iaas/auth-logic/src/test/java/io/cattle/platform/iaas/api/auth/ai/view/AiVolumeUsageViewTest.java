package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.core.dao.VolumeDao;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.tables.records.MountRecord;
import io.cattle.platform.core.model.tables.records.VolumeRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.github.ibuildthecloud.gdapi.model.Field;
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

public class AiVolumeUsageViewTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void activeAndStoppedSameNameReferencesUseFreshOwnersAndAuthorizedStackNames() {
        Fixture f = new Fixture(); f.mounts.add(mount(1, 11, "ro")); f.mounts.add(mount(2, 12, "rw"));
        f.resources.put("container:11", resource("11", "container", "running", Map.of("stackId", "31")));
        f.resources.put("container:12", resource("12", "container", "stopped", Map.of("stackId", "32")));
        f.resources.put("stack:31", resource("31", "stack", "active", Map.of("name", "Blue")));
        f.resources.put("stack:32", resource("32", "stack", "active", Map.of("name", "Green")));
        List<?> refs = references(f.read()); assertEquals(2, refs.size());
        assertEquals("Blue", ((Map<?, ?>) refs.get(0)).get("stackName"));
        assertEquals("Green", ((Map<?, ?>) refs.get(1)).get("stackName"));
        assertEquals(true, ((Map<?, ?>) refs.get(0)).get("readOnly"));
        assertEquals("stopped", ((Map<?, ?>) refs.get(1)).get("state"));
        assertFalse(refs.toString().contains("PRIVATE"));
        verify(f.service.keyAuthorization, times(5)).actor(any());
        assertSame(f.originalContext, ApiContext.getContext().getApiRequest());
    }

    @Test public void hiddenForeignRemovedOrUnreadableParentsNeverExposeIdentitiesOrCounts() {
        Fixture f = new Fixture();
        for (int id = 11; id <= 14; id++) f.mounts.add(mount(id, id, "rw"));
        f.resources.put("container:11", resource("11", "container", "stopped", Map.of("stackId", "31")));
        f.resources.put("container:12", resource("12", "container", "running", Map.of("accountId", "88", "name", "PRIVATE_FOREIGN")));
        f.resources.put("container:13", resource("13", "container", "removed", Map.of("removed", "yesterday", "name", "PRIVATE_REMOVED")));
        f.resources.put("container:14", resource("14", "container", "running", Map.of("name", "PRIVATE_DENIED")));
        f.nativeScope = (type, id) -> !"14".equals(id);
        Map<?, ?> result = f.read(); assertEquals(1, references(result).size());
        assertFalse(result.toString().contains("PRIVATE")); assertFalse(result.containsKey("hiddenCount"));
        assertEquals("unknown", result.get("usageReferencesStatus"));
        assertEquals("unknown", ((Map<?, ?>) references(result).getFirst()).get("stackResolution"));
        verify(f.manager).getById(eq("container"), eq("14"), any());
    }

    @Test public void boundedSnapshotAndEmptyOrLegacyMetadataNeverClaimUnusedOrComplete() {
        Fixture f = new Fixture();
        for (int id = 1; id <= 51; id++) { f.mounts.add(mount(id, 100 + id, "rw")); f.resources.put("container:" + (100 + id),
                resource("" + (100 + id), "container", "stopped", Map.of())); }
        Map<?, ?> result = f.read(); assertEquals(50, references(result).size()); assertEquals("unknown", result.get("usageReferencesStatus"));
        verify(f.manager, never()).getById(eq("container"), eq("151"), any());
        f.mounts.clear(); result = f.read(); assertEquals(List.of(), references(result)); assertEquals("unknown", result.get("usageReferencesStatus"));
        f.service.read(f.request(Map.of("resourceType", "volume"))); verify(f.dao, times(2)).getUsageMounts(77, 7);
        assertFalse(AiResourceViewService.project("volume", "7", Map.of("mounts", List.of("PRIVATE"))).containsKey("usageReferences"));
    }

    @Test public void realCustomKeyOwnerAuditsAllowAndDenyWithReadOnlyDispatchAndNoExtraGrant() throws Exception {
        Fixture f = new Fixture(); f.mounts.add(mount(1, 11, "rw")); f.mounts.add(mount(2, 12, "ro"));
        f.resources.put("container:11", resource("11", "container", "stopped", Map.of()));
        f.resources.put("container:12", resource("12", "container", "running", Map.of("name", "PRIVATE_DENIED")));
        ApiKeyAuthorizationService access = new ApiKeyAuthorizationService(); ApiKeyTargetResolver targets = mock(ApiKeyTargetResolver.class);
        List<String> decisions = new ArrayList<>(); ApiKeyAuditSink sink = mock(ApiKeyAuditSink.class);
        doAnswer(call -> {
            ApiRequest r = call.getArgument(0); assertEquals("GET", r.getMethod());
            assertEquals("read", r.getAttribute("apiKey.audit.operation"));
            assertEquals("5", r.getAttribute("apiKey.audit.keyId")); assertEquals(3L, r.getAttribute("apiKey.audit.policyRevision"));
            assertEquals(42L, r.getAttribute("apiKey.audit.verifiedPrincipalAccountId")); assertSame(f.live, call.getArgument(1));
            decisions.add(r.getType() + ":" + r.getId() + ":" + r.getAttribute("apiKey.audit.decision")); return null;
        }).when(sink).recordDecision(any(), any());
        field(access, "targets", targets); field(access, "auditSinks", List.of(sink));
        when(targets.resolve(any(), any())).thenAnswer(call -> { ApiRequest r = call.getArgument(0);
            return List.of(ApiKeyPolicyEvaluator.Target.projectResource(r.getType(), r.getId(), "77")); });
        when(targets.stackLifecycleTargets(any(), any())).thenReturn(List.of()); when(targets.references(any(), any())).thenReturn(List.of());
        when(targets.parseScopeId(anyString(), anyString())).thenAnswer(call -> Long.valueOf(call.<String>getArgument(1)));
        f.service.keyAccess = access;
        ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
            new ApiKeyPolicy.Rule("volume", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("volume", "7"), Set.of("read")),
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
            f.nativeScope = (type, id) -> {
                if (!"container".equals(type)) return true;
                var table = io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
                var restriction = access.constrain(ApiContext.getContext().getApiRequest(), f.schemas, "instance", table);
                return sql.fetchExists(sql.selectOne().from(table).where(table.ID.eq(Long.valueOf(id))).and(restriction));
            };
            ApiRequest request = f.request(Map.of("resourceType", "volume", "resourceId", "7")); ApiKeyCredentialContext.attach(request, key);
            Map<?, ?> result = (Map<?, ?>) ((List<?>) f.service.read(request).get("data")).getFirst();
            assertEquals(1, references(result).size()); assertFalse(result.toString().contains("PRIVATE_DENIED"));
            assertEquals(List.of("volume:7:ALLOW"), decisions); // No hidden target ID/count enters audit.
            verify(f.manager).getById(eq("container"), eq("12"), any());
            ApiRequest direct = new ApiRequest(null, f.schemas); direct.setType("container"); direct.setId("12"); direct.setMethod("GET");
            ApiKeyCredentialContext.attach(direct, key);
            assertThrows(ClientVisibleException.class, () -> access.authorize(direct, f.live));
            assertEquals(List.of("volume:7:ALLOW", "container:12:DENY"), decisions); // Client supplied this exact target.
        }
    }

    @Test public void unattestedCustomKeyManagerReturnsUnknownWithoutTargetInspectionOrFallback() {
        Fixture f = new Fixture(); f.mounts.add(mount(1, 11, "rw"));
        ApiRequest request = f.request(Map.of("resourceType", "volume", "resourceId", "7"));
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKey", 3,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null, List.of())));
        when(f.manager.supportsScopedCollectionQuery(any())).thenReturn(false);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) f.service.read(request).get("data")).getFirst();
        assertEquals("unknown", row.get("usageReferencesStatus")); assertTrue(references(row).isEmpty());
        verify(f.manager, never()).getById(eq("container"), anyString(), any());
    }

    @Test public void laterTargetKeyRevisionRevocationAbortsTheWholeReadAndRestoresContext() {
        Fixture f = new Fixture(); f.mounts.add(mount(1, 11, "rw")); f.mounts.add(mount(2, 12, "rw"));
        f.resources.put("container:11", resource("11", "container", "stopped", Map.of()));
        when(f.service.keyAuthorization.actor(any())).thenReturn(f.owner, f.owner).thenThrow(new ClientVisibleException(403, "ApiKeyPolicyChanged"));
        ClientVisibleException rejected = assertThrows(ClientVisibleException.class, f::read);
        assertEquals("ApiKeyPolicyChanged", rejected.getCode());
        verify(f.manager, never()).getById(eq("container"), eq("12"), any());
        assertSame(f.originalContext, ApiContext.getContext().getApiRequest());
        assertSame(f.owner, ApiContext.getContext().getPolicy());
    }

    @Test public void nativeCoreProjectionRetainsSchemaFieldVisibilityAndNeverInvokesDtoAugmentation() {
        Fixture f = new Fixture(); f.mounts.add(mount(1, 11, "rw"));
        f.resources.put("container:11", resource("11", "container", "stopped", Map.of("stackId", "31")));
        f.resources.put("stack:31", resource("31", "stack", "active", Map.of("name", "Blue")));
        Field state = f.fields.get("state"); when(state.isReadOnCreateOnly()).thenReturn(true);
        assertTrue(references(f.read()).isEmpty());
        when(state.isReadOnCreateOnly()).thenReturn(false); f.fields.remove("stackId");
        Map<?, ?> result = f.read(); assertEquals(1, references(result).size());
        assertFalse(result.toString().contains("Blue"));
        assertEquals("unknown", ((Map<?, ?>) references(result).getFirst()).get("stackResolution"));
        f.fields.remove("name"); assertTrue(references(f.read()).isEmpty());
        verify(f.manager, never()).convertResponse(any(Object.class), any());
    }

    @Test public void volumeScalarsMatchOriginalNativeWrappingForJwtFullAndLegacyIncludingSchemaAliasesAndTransforms() {
        for (int mode = 0; mode < 3; mode++) {
            Fixture f = new Fixture();
            VolumeRecord volume = new VolumeRecord(); volume.setId(7L); volume.setAccountId(77L);
            volume.setName("named-volume"); volume.setDescription("kept description"); volume.setState("active"); volume.setKind("volume");
            volume.setSizeMb(8192L);
            volume.setData(Map.of("fields", Map.of("driver", "persisted-driver", "isHostPath", false, "isNative", true,
                    "accessMode", "persisted-access", "launchConfig", Map.of("secret", "NEVER_COPY"))));
            f.nativeRows.put("volume:7", volume);
            for (String name : List.of("driver", "accessMode", "sizeMb", "created", "isNative", "isHostPath")) {
                Field field = mock(Field.class); when(field.isIncludeInList()).thenReturn(true);
                when(field.getTypeEnum()).thenReturn(io.github.ibuildthecloud.gdapi.model.FieldType.STRING);
                Object value = "sizeMb".equals(name) ? 8192L : "isNative".equals(name) ? true : "isHostPath".equals(name) ? false
                        : "created".equals(name) ? "2026-11-05T00:00:00Z" : "native-" + name;
                when(field.getValue(volume)).thenReturn(value); f.fields.put(name, field);
            }
            var fullSchema = new io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl(); fullSchema.setId("volume"); fullSchema.setResourceFields(f.fields);
            var oldNative = new io.github.ibuildthecloud.gdapi.model.impl.WrappedResource(
                    ApiContext.getContext().getIdFormatter(), f.schemas, fullSchema, volume,
                    new LinkedHashMap<>(io.cattle.platform.object.util.DataUtils.getFields(volume)), Set.of(), "GET", false);
            Map<String, Object> expected = AiResourceViewService.project("volume", oldNative.getId(), oldNative.getFields());
            expected.put("sizeMb", oldNative.getFields().get("sizeMb"));
            expected.put("isNative", oldNative.getFields().get("isNative")); expected.put("isHostPath", oldNative.getFields().get("isHostPath"));
            ApiRequest request = f.request(Map.of("resourceType", "volume", "resourceId", "7"));
            if (mode != 0) ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(5, 42, "apiKey", 3,
                    mode == 1 ? new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()) : null));
            Map<String, Object> actual = new LinkedHashMap<>((Map<String, Object>) ((List<?>) f.service.read(request).get("data")).getFirst());
            actual.remove("usageReferences"); actual.remove("usageReferencesStatus");
            assertEquals(expected, actual); assertEquals("persisted-driver", actual.get("driver"));
            assertEquals(true, actual.get("isNative")); assertEquals(false, actual.get("isHostPath"));
            assertEquals(8192L, actual.get("sizeMb")); assertEquals("2026-11-05T00:00:00Z", actual.get("created"));
            verify(f.manager, never()).convertResponse(any(Object.class), any());
            Field sensitive = f.fields.get("driver"); clearInvocations(sensitive);
            when(sensitive.getTransform()).thenReturn("native-sensitive-transform");
            Map<?, ?> redacted = (Map<?, ?>) ((List<?>) f.service.read(request).get("data")).getFirst();
            assertFalse(redacted.containsKey("driver")); verify(sensitive, never()).getValue(any());
            when(f.fields.get("isHostPath").isReadOnCreateOnly()).thenReturn(true);
            Map<?, ?> createOnly = (Map<?, ?>) ((List<?>) f.service.read(request).get("data")).getFirst();
            assertFalse(createOnly.containsKey("isHostPath")); assertFalse(createOnly.containsKey("launchConfig"));
        }
    }

    private static void field(Object owner, String name, Object value) throws Exception { var f = owner.getClass().getDeclaredField(name);
            f.setAccessible(true); f.set(owner, value); }
    private static List<?> references(Map<?, ?> row) { return (List<?>) row.get("usageReferences"); }
    private static Mount mount(long id, long container, String permissions) { MountRecord row = new MountRecord(); row.setId(id);
            row.setVolumeId(7L); row.setInstanceId(container); row.setPath("/data"); row.setPermissions(permissions); return row; }
    private static Resource resource(String id, String type, String state, Map<String, Object> extra) { Map<String,
            Object> fields = new LinkedHashMap<>(Map.of("accountId", "77", "name", "same", "state", state, "launchConfig", "PRIVATE", "hostPath",
            "PRIVATE")); fields.putAll(extra); return new ResourceImpl(id, type, fields); }
    static class Fixture extends AiResourceViewServiceTest.Fixture {
        final VolumeDao dao = mock(VolumeDao.class);
        final List<Mount> mounts = new ArrayList<>();
        final Map<String, Resource> resources = new LinkedHashMap<>();
        final Map<String, Object> nativeRows = new LinkedHashMap<>();
        final Map<String, Field> fields = new LinkedHashMap<>();
        java.util.function.BiPredicate<String, String> nativeScope = (type, id) -> true;
        Fixture() {
            when(live.getAccountId()).thenReturn(77L); when(live.getAuthenticatedAsAccountId()).thenReturn(42L);
            service.volumes = dao; when(dao.getUsageMounts(77, 7)).thenAnswer(call -> mounts);
            when(manager.supportsScopedCollectionQuery(any())).thenReturn(true);
            resources.put("volume:7", resource("7", "volume", "active", Map.of("mounts", "PRIVATE_UNBOUNDED")));
            when(schema.getResourceFields()).thenReturn(fields);
            when(schema.getId()).thenAnswer(call -> ApiContext.getContext().getApiRequest().getType());
            for (String name : List.of("id", "name", "state", "stackId", "description", "kind")) {
                Field field = mock(Field.class); when(field.isIncludeInList()).thenReturn(true);
                when(field.getTypeEnum()).thenReturn(io.github.ibuildthecloud.gdapi.model.FieldType.STRING);
                if ("stackId".equals(name)) {
                    when(field.getTypeEnum()).thenReturn(io.github.ibuildthecloud.gdapi.model.FieldType.REFERENCE);
                    when(field.getSubTypes()).thenReturn(List.of("stack"));
                }
                when(field.getValue(any())).thenAnswer(call -> {
                    Object value = call.getArgument(0);
                    return value.getClass().getMethod("get" + Character.toUpperCase(name.charAt(0)) + name.substring(1)).invoke(value);
                }); fields.put(name, field);
            }
            when(manager.getById(anyString(), anyString(), any())).thenAnswer(call -> {
                String type = call.getArgument(0), id = call.getArgument(1);
                if (!nativeScope.test(type, id)) return null;
                if (nativeRows.containsKey(type + ":" + id)) return nativeRows.get(type + ":" + id);
                Resource r = resources.get(type + ":" + id);
                if (r == null) return null;
                Long account = Long.valueOf(r.getFields().get("accountId").toString());
                java.util.Date removed = r.getFields().get("removed") == null ? null : new java.util.Date();
                if ("volume".equals(type)) {
                    VolumeRecord v = new VolumeRecord(); v.setId(Long.valueOf(r.getId())); v.setAccountId(account);
                    v.setName(r.getFields().get("name").toString()); v.setState(r.getFields().get("state").toString());
                    v.setRemoved(removed); return v;
                }
                if ("container".equals(type)) {
                    InstanceRecord v = new InstanceRecord(); v.setId(Long.valueOf(r.getId())); v.setAccountId(account); v.setKind("container");
                    v.setName(r.getFields().get("name").toString()); v.setState(r.getFields().get("state").toString()); v.setRemoved(removed);
                    if (r.getFields().get("stackId") != null) v.setStackId(Long.valueOf(r.getFields().get("stackId").toString())); return v;
                }
                StackRecord v = new StackRecord(); v.setId(Long.valueOf(r.getId())); v.setAccountId(account);
                v.setName(r.getFields().get("name").toString()); v.setState(r.getFields().get("state").toString()); v.setRemoved(removed); return v;
            });
        }
        Map<?, ?> read() {
            return (Map<?, ?>) ((List<?>) service.read(request(Map.of("resourceType", "volume", "resourceId", "7"))).get("data")).getFirst();
        }
    }
}
