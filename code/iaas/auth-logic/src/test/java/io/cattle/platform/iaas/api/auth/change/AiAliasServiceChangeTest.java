package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceConsumeMap;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class AiAliasServiceChangeTest {
    @Test public void creationIsStoppedAndEmptyWhileTargetBindingIsASeparateClosedNativeAction() {
        var create = AiResourceChangeDescriptor.require("dnsService", "create");
        var body = create.body(create.parameters(Map.of("name", "alias", "stackId", "1st8")));
        assertEquals(Map.of("type", "dnsService", "name", "alias", "stackId", "1st8", "startOnCreate", false), body);
        assertFalse(body.containsKey("serviceLinks")); assertFalse(body.containsKey("image")); assertFalse(body.containsKey("launchConfig"));
        var links = AiResourceChangeDescriptor.require("dnsService", "update_targets");
        assertEquals("setservicelinks", links.action()); assertEquals("update", links.keyOperation());
        assertEquals(Map.of("serviceLinks", List.of(Map.of("serviceId", "1s9"))), links.body(links.parameters(Map.of("serviceIds", List.of("1s9")))));
        assertEquals(Map.of("serviceLinks", List.of()), links.body(links.parameters(Map.of("serviceIds", List.of()))));
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of(), Map.<String, Object>of("serviceIds", List.of("1s9", "1s9")),
                Map.<String, Object>of("serviceIds", java.util.Collections.nCopies(17, "1s9")),
                Map.<String, Object>of("serviceLinks", List.of(Map.of("service", "https://PRIVATE")))))
            assertThrows(ClientVisibleException.class, () -> links.parameters(invalid));
        for (String field : List.of("image", "serviceIds", "serviceLinks", "launchConfig", "startOnCreate", "url")) {
            Map<String, Object> input = new LinkedHashMap<>(Map.of("name", "alias", "stackId", "1st8")); input.put(field, "PRIVATE");
            assertThrows(ClientVisibleException.class, () -> create.parameters(input));
        }
    }

    @Test public void noOpAliasLifecycleObservesTheActualLinkGraphAndRetiredMapsWithoutAnInstance() {
        ServiceRecord service = new ServiceRecord(); service.setId(2L); service.setAccountId(5L); service.setKind("dnsService"); service.setState("inactive");
        ServiceConsumeMapRecord map = new ServiceConsumeMapRecord(); map.setId(7L); map.setServiceId(2L); map.setAccountId(5L);
        map.setConsumedServiceId(9L); map.setState("active");
        assertTrue(AiAliasServiceChange.complete("create", service, Map.of(), Set.of(), List.of()));
        assertFalse(AiAliasServiceChange.complete("create", service, Map.of(), Set.of(), List.of(map)));
        assertTrue(AiAliasServiceChange.complete("stop", service, Map.of(), Set.of(), List.of(map)));
        service.setState("active"); assertTrue(AiAliasServiceChange.complete("start", service, Map.of(), Set.of(), List.of(map)));
        assertTrue(AiAliasServiceChange.complete("update_targets", service, Map.of(), Set.of(9L), List.of(map)));
        map.setConsumedServiceId(10L); assertFalse(AiAliasServiceChange.complete("update_targets", service, Map.of(), Set.of(9L), List.of(map)));
        map.setRemoved(new Date(1)); map.setState("removing");
        assertFalse(AiAliasServiceChange.complete("update_targets", service, Map.of(), Set.of(), List.of(map)));
        map.setState("removed"); assertTrue(AiAliasServiceChange.complete("update_targets", service, Map.of(), Set.of(), List.of(map)));
        service.setRemoved(new Date(1)); service.setState("removed");
        assertTrue(AiAliasServiceChange.complete("remove", service, Map.of(), Set.of(), List.of(map)));
        map.setState("removing"); assertFalse(AiAliasServiceChange.complete("remove", service, Map.of(), Set.of(), List.of(map)));
        service.setKind("service"); assertFalse(AiAliasServiceChange.complete("remove", service, Map.of(), Set.of(), List.of()));
    }

    @Test public void safeReadOnlyLinkSummaryNeverProjectsHiddenNamesIdsOrPrivateReferenceSnapshots() {
        Policy owner = mock(Policy.class); when(owner.getAccountId()).thenReturn(5L);
        ServiceRecord alias = new ServiceRecord(); alias.setId(2L); alias.setKind("dnsService"); alias.setAccountId(5L);
        ServiceConsumeMapRecord link = new ServiceConsumeMapRecord(); link.setId(7L); link.setServiceId(2L);
        final boolean[] readable = {false}; final int[] reads = {0};
        var domain = new AiResourceChangeDomain() {
            @Override public Policy actor(ApiRequest request) { return owner; }
            @Override public Map<String, Object> aliasReferenceSnapshot(ApiRequest request, AiResourceChangeDescriptor descriptor,
                    String id, Map<String, Object> parameters) {
                reads[0]++;
                if (!readable[0]) throw new ClientVisibleException(403, "PermissionDenied", "PRIVATE target", null);
                return Map.of("before", List.of(Map.of("id", 9L, "uuid", "PRIVATE uuid", "path", "Green / db")), "after", List.of());
            }
        };
        domain.objects = mock(ObjectManager.class); domain.targets = mock(ApiKeyTargetResolver.class);
        when(domain.targets.parseScopeId("dnsService", "1s2")).thenReturn(2L);
        when(domain.objects.loadResource(Service.class, 2L)).thenReturn(alias);
        when(domain.objects.find(ServiceConsumeMap.class, "serviceId", 2L, "removed", null)).thenReturn(List.of(link));
        assertEquals(1, domain.aliasViewTargets(new ApiRequest(null, null), "1s2", false).get("unresolvedTargetCount")); assertEquals(0, reads[0]);
        var hidden = domain.aliasViewTargets(new ApiRequest(null, null), "1s2", true);
        assertEquals("[]", hidden.get("targetServices")); assertEquals(1, hidden.get("linkedServiceCount"));
        assertFalse(hidden.toString().contains("PRIVATE")); assertFalse(hidden.containsKey("referenceSnapshot"));
        readable[0] = true; domain.json = new io.cattle.platform.json.JacksonJsonMapper();
        var visible = domain.aliasViewTargets(new ApiRequest(null, null), "1s2", true);
        assertEquals("[\"Green / db\"]", visible.get("targetServices")); assertEquals(0, visible.get("unresolvedTargetCount"));
        assertFalse(visible.toString().contains("PRIVATE")); assertFalse(visible.containsKey("id"));
    }
}
