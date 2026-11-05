package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.core.dao.ServiceDao;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.MountRecord;
import io.cattle.platform.core.model.tables.records.InstanceHostMapRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiUsageServiceStackViewTest {
    private static final String UUID = "11111111-1111-1111-1111-111111111111";
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void realNativeNullInstanceStackAndNoStackFieldUsesReadableSemServiceStackForHostAndVolume() {
        for (String type : List.of("host", "volume")) {
            Fixture f = new Fixture(type); Map<?, ?> row = f.read();
            assertEquals("Blue", ((Map<?, ?>) f.refs(row).getFirst()).get("stackName"));
            assertEquals("resolved", ((Map<?, ?>) f.refs(row).getFirst()).get("stackResolution"));
            assertFalse(f.containerFields.containsKey("stackId")); assertNull(f.instance.getStackId());
            verify(f.manager).getById(eq("service"), eq("51"), any()); verify(f.manager).getById(eq("stack"), eq("31"), any());
            verify(f.services).isUsageServiceCurrent(77, 11, UUID, 51, 31, "host".equals(type) ? 7L : null, "host".equals(type) ? 1L : null);
            verify(f.manager, never()).convertResponse(any(Object.class), any());
            assertFalse(row.toString().contains("PRIVATE")); assertFalse(row.toString().contains("serviceIds"));
        }
    }

    @Test public void hiddenTransformedNonNullDirectOrMultipleSemRelationsNeverBypassReadableSchemaOrLeakNames() {
        for (String scenario : List.of("hiddenRelation", "transformed", "createOnly", "hiddenDirect", "multiple", "hiddenService",
                "hiddenStack", "hiddenServiceStack", "otherKind", "unattested")) {
            Fixture f = new Fixture("volume");
            if ("hiddenRelation".equals(scenario)) f.containerFields.remove("serviceIds");
            if ("transformed".equals(scenario)) when(f.relation.getTransform()).thenReturn("private-transform");
            if ("createOnly".equals(scenario)) when(f.relation.isReadOnCreateOnly()).thenReturn(true);
            if ("hiddenDirect".equals(scenario)) f.instance.setStackId(99L);
            if ("multiple".equals(scenario)) when(f.services.getUsageServiceIds(77, 11, UUID)).thenReturn(List.of(51L, 52L));
            if ("hiddenService".equals(scenario)) f.nativeScope = (type, id) -> !"service".equals(type);
            if ("hiddenStack".equals(scenario)) f.nativeScope = (type, id) -> !"stack".equals(type);
            if ("hiddenServiceStack".equals(scenario)) f.fields.remove("stackId");
            if ("otherKind".equals(scenario)) f.serviceRow.setKind("loadBalancerService");
            if ("unattested".equals(scenario)) {
                when(f.manager.supportsScopedCollectionQuery(any())).thenAnswer(call -> !"service".equals(ApiContext.getContext().getApiRequest().getType()));
                f.key = new ApiKeyCredentialContext(5, 42, "apiKey", 3,
                        new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
            }
            Map<?, ?> row = f.read(); assertEquals(1, f.refs(row).size());
            Map<?, ?> ref = (Map<?, ?>) f.refs(row).getFirst(); assertEquals("unknown", ref.get("stackResolution"));
            assertFalse(ref.containsKey("stackName")); assertFalse(ref.containsKey("serviceId")); assertFalse(ref.containsKey("hiddenCount"));
            if (List.of("hiddenRelation", "transformed", "createOnly", "hiddenDirect").contains(scenario)) verifyNoInteractions(f.services);
            if ("multiple".equals(scenario) || "unattested".equals(scenario)) verify(f.manager, never()).getById(eq("service"), anyString(), any());
        }
    }

    @Test public void exactCapturedSemAndStackRaceDropsFormerNameWhileChangedKeyAbortsTheWholeRead() {
        for (String type : List.of("host", "volume")) {
            Fixture f = new Fixture(type); when(f.services.isUsageServiceCurrent(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), any(), any()))
                    .thenReturn(false);
            Map<?, ?> row = f.read(); assertEquals(1, f.refs(row).size());
            assertEquals("unknown", ((Map<?, ?>) f.refs(row).getFirst()).get("stackResolution")); assertFalse(row.toString().contains("Blue"));
            verify(f.services).isUsageServiceCurrent(77, 11, UUID, 51, 31, "host".equals(type) ? 7L : null, "host".equals(type) ? 1L : null);
        }
        Fixture f = new Fixture("volume");
        when(f.service.keyAuthorization.actor(any())).thenReturn(f.owner, f.owner).thenThrow(new ClientVisibleException(403, "ApiKeyPolicyChanged"));
        assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class, f::read).getCode());
        assertSame(f.originalContext, ApiContext.getContext().getApiRequest()); assertSame(f.owner, ApiContext.getContext().getPolicy());
    }

    static class Fixture extends AiHostWorkloadViewTest.Fixture {
        final ServiceDao services = mock(ServiceDao.class); final String type;
        final Map<String, Field> containerFields = new LinkedHashMap<>(); final Field relation = mock(Field.class);
        final InstanceRecord instance = new InstanceRecord(); final ServiceRecord serviceRow = new ServiceRecord();
        ApiKeyCredentialContext key;
        Fixture(String type) {
            this.type = type; service.services = services;
            when(services.getUsageServiceIds(77, 11, UUID)).thenReturn(List.of(51L));
            when(services.isUsageServiceCurrent(anyLong(), anyLong(), anyString(), anyLong(), anyLong(), any(), any())).thenReturn(true);
            instance.setId(11L); instance.setUuid(UUID); instance.setAccountId(77L); instance.setKind("container");
            instance.setName("same"); instance.setState("stopped"); nativeRows.put("container:11", instance);
            serviceRow.setId(51L); serviceRow.setAccountId(77L); serviceRow.setKind("service"); serviceRow.setStackId(31L);
            nativeRows.put("service:51", serviceRow); resources.put("stack:31", new ResourceImpl("31", "stack",
                    Map.of("accountId", "77", "name", "Blue", "state", "active")));
            containerFields.putAll(fields); containerFields.remove("stackId"); containerFields.put("serviceIds", relation);
            when(relation.isIncludeInList()).thenReturn(true);
            Schema container = mock(Schema.class); when(container.getId()).thenReturn("container");
            when(container.getResourceFields()).thenReturn(containerFields); when(container.getResourceMethods()).thenReturn(List.of("GET"));
            when(schemas.getSchema("container")).thenReturn(container);
            MountRecord mount = new MountRecord(); mount.setId(1L); mount.setVolumeId(7L); mount.setInstanceId(11L); mount.setPath("/data");
            mount.setPermissions("rw"); mounts.add(mount);
            InstanceHostMapRecord placement = new InstanceHostMapRecord(); placement.setId(1L); placement.setHostId(7L);
            placement.setInstanceId(11L); candidates.add(placement);
        }
        @Override Map<?, ?> read() {
            var request = request(Map.of("resourceType", type, "resourceId", "7")); if (key != null) ApiKeyCredentialContext.attach(request, key);
            return (Map<?, ?>) ((List<?>) service.read(request).get("data")).getFirst();
        }
        List<?> refs(Map<?, ?> row) { return (List<?>) row.get("host".equals(type) ? "workloadReferences" : "usageReferences"); }
    }
}
