package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiHostSearchTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void hostnameAndCustomNameAreSearchableWithoutRewritingTheName() {
        var f = new AiResourceViewServiceTest.Fixture();
        f.rows.add(host("1", null, "native.example"));
        f.rows.add(host("2", "Friendly name", "custom.example"));
        for (String query : List.of("NATIVE", "friendly", "CUSTOM")) {
            var result = f.service.read(f.request(Map.of("resourceType", "host", "query", query)));
            var rows = (List<?>) result.get("data"); assertEquals(1, rows.size());
            var row = (Map<?, ?>) rows.getFirst();
            assertEquals("NATIVE".equals(query) ? null : "Friendly name", row.get("name"));
            assertEquals("NATIVE".equals(query) ? "native.example" : "custom.example", row.get("hostname"));
        }
        verify(f.manager, times(3)).list(eq("host"), argThat((ApiRequest request) ->
                !request.getConditions().containsKey("name") && request.getPagination().getLimit() == 51));
        verify(f.keyAccess, times(3)).authorize(any(), any());
    }

    @Test public void hostnameSearchKeepsBoundedNativePagesAndTheirContinuation() {
        var f = new AiResourceViewServiceTest.Fixture();
        List<Resource> authorized = new ArrayList<>();
        for (int id = 1; id <= 60; id++) authorized.add(host(Integer.toString(id), null, "unrelated-" + id));
        authorized.add(host("61", null, "wanted.example"));
        when(f.manager.list(eq("host"), any(ApiRequest.class))).thenAnswer(call -> {
            ApiRequest request = call.getArgument(1);
            assertFalse(request.getConditions().containsKey("name"));
            long after = request.getConditions().containsKey("id")
                    ? Long.parseLong(request.getConditions().get("id").getFirst().getValue().toString()) : 0;
            if (after > 0) assertEquals(ConditionType.GT, request.getConditions().get("id").getFirst().getConditionType());
            assertEquals(51, request.getPagination().getLimit().intValue());
            return authorized.stream().filter(row -> Long.parseLong(row.getId()) > after).limit(51).toList();
        });
        var first = f.service.read(f.request(Map.of("resourceType", "host", "query", "wanted")));
        assertEquals(List.of(), first.get("data")); assertEquals("50", first.get("nextMarker"));
        var next = f.service.read(f.request(Map.of("resourceType", "host", "query", "wanted", "marker", "50")));
        assertNull(next.get("nextMarker")); assertEquals("61", ((Map<?, ?>) ((List<?>) next.get("data")).getFirst()).get("id"));
        verify(f.keyAccess, times(2)).authorize(any(), any());
    }

    @Test public void duplicateHostnamesRemainCandidatesAndTheNativeOwnerAloneSuppliesRows() {
        var f = new AiResourceViewServiceTest.Fixture();
        f.rows.add(host("1", null, "duplicate.example")); f.rows.add(host("2", "", "duplicate.example"));
        var result = f.service.read(f.request(Map.of("resourceType", "host", "query", "duplicate")));
        assertEquals(2, ((List<?>) result.get("data")).size());
        verify(f.manager).list(eq("host"), any(ApiRequest.class));
        verify(f.manager, never()).getById(anyString(), anyString(), any());
        verify(f.authenticator).currentAuthorization(eq(42L), eq(77L), any());
    }

    @Test public void hostSearchStillStopsAtNativeRoleAndKeyDenials() {
        var f = new AiResourceViewServiceTest.Fixture();
        when(f.schema.getCollectionMethods()).thenReturn(List.of());
        try { f.service.read(f.request(Map.of("resourceType", "host", "query", "native"))); fail(); }
        catch (ClientVisibleException expected) { assertEquals(403, expected.getStatus()); }
        verifyNoInteractions(f.manager);
        when(f.schema.getCollectionMethods()).thenReturn(List.of("GET"));
        doThrow(new ClientVisibleException(403, "KeyScopeDenied")).when(f.keyAccess).authorize(any(), any());
        try { f.service.read(f.request(Map.of("resourceType", "host", "query", "native"))); fail(); }
        catch (ClientVisibleException expected) { assertEquals(403, expected.getStatus()); }
        verifyNoInteractions(f.manager);
    }

    private static Resource host(String id, String name, String hostname) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", name); fields.put("hostname", hostname); fields.put("state", "active");
        return new ResourceImpl(id, "host", fields);
    }
}
