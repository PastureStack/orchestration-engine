package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiRegistryLifecycleViewTest {
    @Test public void registryEndpointSearchRetainsBoundedNativeRoleAndKeyQueryWithoutInventingNameMatches() {
        var f = new AiResourceViewServiceTest.Fixture();
        for (String address : List.of("other.example", "registry.example:5000", "user:PRIVATE@registry.example")) {
            var row = new ResourceImpl(); row.setId(Integer.toString(f.rows.size() + 1)); row.setType("registry");
            row.getFields().put("serverAddress", address); row.getFields().put("state", "active"); f.rows.add(row);
        }
        var legacy = new ResourceImpl(); legacy.setId("4"); legacy.setType("registry");
        legacy.getFields().put("name", "existing-registry"); legacy.getFields().put("serverAddress", "unsafe/path"); f.rows.add(legacy);
        var result = f.service.read(f.request(Map.of("resourceType", "registry", "query", "registry.example")));
        var rows = (List<?>) result.get("data"); assertEquals(1, rows.size());
        assertEquals("registry.example:5000", ((Map<?, ?>) rows.getFirst()).get("serverAddress"));
        assertFalse(result.toString().contains("PRIVATE")); assertFalse(((Map<?, ?>) rows.getFirst()).containsKey("name"));
        var existing = (List<?>) f.service.read(f.request(Map.of("resourceType", "registry", "query", "existing-registry"))).get("data");
        assertEquals(1, existing.size()); assertEquals("existing-registry", ((Map<?, ?>) existing.getFirst()).get("name"));
        assertEquals("unknown", ((Map<?, ?>) existing.getFirst()).get("addressStatus"));
        var request = org.mockito.ArgumentCaptor.forClass(ApiRequest.class);
        verify(f.manager, times(2)).list(eq("registry"), request.capture()); assertFalse(request.getValue().getConditions().containsKey("name"));
        verify(f.keyAccess, times(2)).authorize(any(), eq(f.live));
        assertTrue(request.getValue().getPagination().getLimit() <= 51);
        io.github.ibuildthecloud.gdapi.context.ApiContext.remove();
    }
}
