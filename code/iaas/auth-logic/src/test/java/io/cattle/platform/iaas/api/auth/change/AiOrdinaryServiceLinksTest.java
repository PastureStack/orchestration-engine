package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.model.*;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.*;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Set;
import java.util.Date;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiOrdinaryServiceLinksTest {
    @Test public void nativePlanInputSchemaAdmitsOnlyTheNewTypedOperationWithoutExposingPrivatePins() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("resources/content/schema/base/aiResourceChangePlan.json")))
            root = root.getParent();
        assertNotNull("Repository native schema source", root);
        try (var input = java.nio.file.Files.newInputStream(root.resolve("resources/content/schema/base/aiResourceChangePlan.json"))) {
            Map<String, Object> schema = new JacksonJsonMapper().readValue(input.readAllBytes());
            Map<?, ?> fields = (Map<?, ?>) schema.get("resourceFields");
            assertTrue(((List<?>) ((Map<?, ?>) fields.get("operation")).get("options")).contains("update_links"));
            assertFalse(fields.containsKey("linkIdentity")); assertFalse(fields.containsKey("referenceSnapshot"));
        }
    }

    @Test public void ordinaryDependencyLinksUseOnlyTheClosedNativeSetAction() {
        var descriptor = AiResourceChangeDescriptor.require("service", "update_links");
        assertEquals("POST", descriptor.method());
        assertEquals("setservicelinks", descriptor.action());
        assertEquals("update", descriptor.keyOperation());
        assertEquals(Map.of("serviceLinks", List.of(Map.of("serviceId", "1s9"))),
                descriptor.body(descriptor.parameters(Map.of("serviceIds", List.of("1s9")))));
        assertEquals(Map.of("serviceLinks", List.of()), descriptor.body(descriptor.parameters(Map.of("serviceIds", List.of()))));
        for (Map<String, Object> input : List.of(Map.<String, Object>of(),
                Map.<String, Object>of("serviceIds", List.of("1s9", "1s9")),
                Map.<String, Object>of("serviceIds", java.util.Collections.nCopies(17, "1s9")),
                Map.<String, Object>of("serviceLinks", List.of(Map.of("service", "PRIVATE"))),
                Map.<String, Object>of("serviceIds", List.of("1s9"), "name", "not-link-input"))) {
            assertThrows(io.github.ibuildthecloud.gdapi.exception.ClientVisibleException.class, () -> descriptor.parameters(input));
        }
    }

    @Test public void completionReadsTheActualSettledSetAndPreservesStateConfigurationAndInstanceStarts() {
        for (String state : List.of("active", "inactive")) {
            var f = new Fixture(); f.service.setState(state);
            Map<String, Object> before = f.domain.serviceLinksSnapshot(f.service);
            assertEquals("source", before.get("uuid")); assertEquals("stack", before.get("linkStackUuid"));
            assertTrue(before.toString().contains("map-uuid")); assertTrue(before.toString().contains("target"));
            assertTrue(f.complete(before));
            f.map.setState("activating"); assertFalse(f.complete(before)); f.map.setState("active");
            f.map.setConsumedServiceId(10L); assertFalse(f.complete(before)); f.map.setConsumedServiceId(9L);
            f.map.setName("named"); assertFalse(f.complete(before)); f.map.setName(null);
            f.service.setState(state.equals("active") ? "inactive" : "active"); assertFalse(f.complete(before)); f.service.setState(state);
            f.service.setData(Map.of("fields", Map.of("launchConfig", Map.of("imageUuid", "changed"))));
            assertFalse(f.complete(before)); f.service.setData(null);
            f.instance.setStartCount(2L); assertFalse(f.complete(before)); f.instance.setStartCount(1L);
            f.service.setSelectorContainer("changed=true"); assertFalse(f.complete(before)); f.service.setSelectorContainer(null);
            f.service.setSelectorLink("auto=true");
            assertThrows(io.github.ibuildthecloud.gdapi.exception.ClientVisibleException.class, () -> f.domain.ready(f.descriptor, f.service, null));
            assertFalse(f.complete(before)); f.service.setSelectorLink(null);
            f.stack.setUuid("replacement"); assertFalse(f.complete(before)); f.stack.setUuid("stack");
            f.service.setUuid("replacement"); assertFalse(f.complete(before)); f.service.setUuid("source");
            f.map.setRemoved(new Date(1)); f.map.setState("removing");
            assertFalse(f.domain.complete(f.descriptor, f.service, Map.of("serviceIds", List.of()), before));
            f.map.setState("removed");
            assertTrue(f.domain.complete(f.descriptor, f.service, Map.of("serviceIds", List.of()), before));
            f.map.setRemoved(null); f.map.setState("active");
            var duplicate = new ServiceConsumeMapRecord(); duplicate.setId(8L); duplicate.setAccountId(5L);
            duplicate.setServiceId(2L); duplicate.setConsumedServiceId(9L); duplicate.setState("active");
            assertFalse(AiAliasServiceChange.complete("update_links", f.service, Map.of(), Set.of(9L), List.of(f.map, duplicate)));
        }
    }

    @Test public void beforeSetIsCollectedFromAllNativeMapsAndComplexOrOversizedSetsReject() {
        var f = new Fixture();
        var owner = mock(io.cattle.platform.api.auth.Policy.class); when(owner.getAccountId()).thenReturn(5L);
        var domain = new AiResourceChangeDomain() {
            @Override public io.cattle.platform.api.auth.Policy actor(ApiRequest request) { return owner; }
            @Override public String publicId(String type, Object id) { return String.valueOf(id); }
            @Override public List<Map<String, Object>> aliasReferences(ApiRequest request, AiResourceChangeDescriptor descriptor, Map<String, Object> parameters) {
                return ((List<?>) parameters.get("serviceIds")).stream().map(id -> Map.<String, Object>of("id", id)).toList();
            }
        };
        domain.objects = f.domain.objects; domain.json = new JacksonJsonMapper(); domain.targets = f.domain.targets;
        // A source without Stack identity must reject before reaching a native reference read.
        f.service.setStackId(null);
        assertThrows(io.github.ibuildthecloud.gdapi.exception.ClientVisibleException.class,
                () -> domain.aliasReferenceSnapshot(new ApiRequest(null, null), f.descriptor, "1s2", Map.of("serviceIds", List.of())));
        f.service.setStackId(8L); f.map.setName("named");
        assertThrows(io.github.ibuildthecloud.gdapi.exception.ClientVisibleException.class,
                () -> domain.aliasReferenceSnapshot(new ApiRequest(null, null), f.descriptor, "1s2", Map.of("serviceIds", List.of())));
        f.map.setName(null); f.map.setConsumedService("remote/service");
        assertThrows(io.github.ibuildthecloud.gdapi.exception.ClientVisibleException.class,
                () -> domain.aliasReferenceSnapshot(new ApiRequest(null, null), f.descriptor, "1s2", Map.of("serviceIds", List.of())));
        f.map.setConsumedService(null);
        List<ServiceConsumeMap> many = new ArrayList<>();
        for (long id = 1; id <= 17; id++) { var map = new ServiceConsumeMapRecord(); map.setAccountId(5L); map.setServiceId(2L); map.setConsumedServiceId(100L + id); many.add(map); }
        when(domain.objects.find(ServiceConsumeMap.class, "serviceId", 2L, "removed", null)).thenReturn(many);
        assertThrows(io.github.ibuildthecloud.gdapi.exception.ClientVisibleException.class,
                () -> domain.aliasReferenceSnapshot(new ApiRequest(null, null), f.descriptor, "1s2", Map.of("serviceIds", List.of())));
    }

    @Test public void nativeIdentityPinsNeverTruncateFractionalNonFiniteOverflowOrStringValues() {
        assertEquals(8L, AiAliasServiceChange.nativeId(8)); assertEquals(8L, AiAliasServiceChange.nativeId(8L));
        assertEquals(8L, AiAliasServiceChange.nativeId(new java.math.BigDecimal("8.0")));
        for (Object invalid : List.of(8.5, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                new java.math.BigInteger("9223372036854775808"), new java.math.BigDecimal("9223372036854775808"),
                "8", 0, -1, 9007199254740992D)) assertEquals(String.valueOf(invalid), -1L, AiAliasServiceChange.nativeId(invalid));
        assertEquals(-1L, AiAliasServiceChange.nativeId(null));
    }

    @Test public void revisionZeroIsAnExactNonnegativeIntegerWhileEntityIdsStayPositive() {
        for (Object revision : List.of(0, 0L, new java.math.BigDecimal("0.0"), 8L))
            assertTrue(AiAliasServiceChange.nativeRevision(revision) >= 0);
        for (Object invalid : List.of(-1, -0.5, 0.5, Double.NaN, Double.POSITIVE_INFINITY,
                new java.math.BigInteger("9223372036854775808"), new java.math.BigDecimal("9223372036854775808"), "0", "8"))
            assertEquals(String.valueOf(invalid), -1L, AiAliasServiceChange.nativeRevision(invalid));
        assertEquals(-1L, AiAliasServiceChange.nativeRevision(null));
        assertEquals(-1L, AiAliasServiceChange.nativeId(0)); assertEquals(8L, AiAliasServiceChange.nativeId(8));
    }

    @Test public void terminalReceiptsEraseConfigurationButFreshlyAuthorizeBothReviewedSides() throws Exception {
        var f = new Fixture(); var owner = mock(io.cattle.platform.api.auth.Policy.class);
        when(owner.getAccountId()).thenReturn(5L); when(owner.getAuthenticatedAsAccountId()).thenReturn(10L);
        when(owner.authorizeObject(f.service)).thenReturn(f.service);
        var schemas = mock(io.github.ibuildthecloud.gdapi.factory.SchemaFactory.class);
        var schema = mock(io.github.ibuildthecloud.gdapi.model.Schema.class);
        when(schemas.getSchema("service")).thenReturn(schema); when(schema.getResourceMethods()).thenReturn(List.of("GET"));
        var beforePin = Map.<String, Object>of("id", 9, "uuid", "old", "accountId", 5, "stackId", 9, "stackUuid", "old-stack", "kind", "service", "path", "PRIVATE old name");
        var afterPin = Map.<String, Object>of("id", 10, "uuid", "new", "accountId", 5, "stackId", 10, "stackUuid", "new-stack", "kind", "service", "path", "PRIVATE new name");
        Set<String> hidden = new java.util.HashSet<>(); List<String> reads = new ArrayList<>();
        var domain = new AiResourceChangeDomain() {
            @Override public io.cattle.platform.api.auth.Policy actor(ApiRequest request) { return owner; }
            @Override public Object load(String type, long id) { return f.service; }
            @Override public Object visible(ApiRequest request, AiResourceChangeDescriptor descriptor, String id) { return f.service; }
            @Override public String publicId(String type, Object id) { return "1s" + id; }
            @Override public List<Map<String, Object>> aliasReferences(ApiRequest request, AiResourceChangeDescriptor descriptor, Map<String, Object> parameters) {
                List<Map<String, Object>> result = new ArrayList<>();
                for (Object id : (List<?>) parameters.get("serviceIds")) {
                    reads.add((String) id);
                    if (hidden.contains(id)) throw new io.github.ibuildthecloud.gdapi.exception.ClientVisibleException(403, "OwnerPermissionDenied");
                    if (!id.equals("1s2")) result.add(id.equals("1s9") ? beforePin : afterPin);
                }
                return result;
            }
        };
        domain.objects = f.domain.objects; domain.json = f.domain.json;
        domain.authenticator = mock(io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.class);
        when(domain.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(
                new io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.CurrentAuthorization(owner, schemas));
        Map<String, Object> plan = new java.util.LinkedHashMap<>(Map.of("resourceType", "service", "operation", "update_links",
                "internalResourceId", 2L, "resourceId", "1s2", "status", "succeeded", "snapshot", f.domain.serviceLinksSnapshot(f.service),
                "referenceSnapshot", Map.of("before", List.of(beforePin), "after", List.of(afterPin))));
        var erase = AiResourceChangePlanService.class.getDeclaredMethod("eraseSnapshot", Map.class); erase.setAccessible(true);
        erase.invoke(new AiResourceChangePlanService(), plan);
        assertFalse(plan.containsKey("snapshot")); assertTrue(plan.containsKey("linkIdentity"));
        assertSame(f.service, domain.receiptTarget(new ApiRequest(null, null), f.descriptor, plan));
        assertEquals(List.of("1s2", "1s9", "1s10"), reads);
        for (String hiddenId : List.of("1s2", "1s9", "1s10")) {
            hidden.add(hiddenId);
            assertThrows(io.github.ibuildthecloud.gdapi.exception.ClientVisibleException.class,
                    () -> domain.receiptTarget(new ApiRequest(null, null), f.descriptor, plan));
            hidden.clear();
        }
        var output = new AiResourceChangePlanService().output(plan);
        assertFalse(output.containsKey("linkIdentity")); assertFalse(output.containsKey("referenceSnapshot"));
        assertFalse(output.toString().contains("PRIVATE"));
    }

    private static class Fixture {
        final AiResourceChangeDomain domain = new AiResourceChangeDomain();
        final AiResourceChangeDescriptor descriptor = AiResourceChangeDescriptor.require("service", "update_links");
        final ServiceRecord service = new ServiceRecord(), target = new ServiceRecord();
        final StackRecord stack = new StackRecord(); final ServiceConsumeMapRecord map = new ServiceConsumeMapRecord();
        final InstanceRecord instance = new InstanceRecord();
        Fixture() {
            domain.objects = mock(ObjectManager.class); domain.targets = mock(ApiKeyTargetResolver.class); domain.json = new JacksonJsonMapper();
            service.setId(2L); service.setUuid("source"); service.setAccountId(5L); service.setStackId(8L); service.setKind("service"); service.setState("active");
            target.setId(9L); target.setUuid("target"); target.setAccountId(5L); target.setStackId(9L); target.setKind("service");
            stack.setId(8L); stack.setUuid("stack"); stack.setAccountId(5L); stack.setState("active");
            map.setId(7L); map.setUuid("map-uuid"); map.setAccountId(5L); map.setServiceId(2L); map.setConsumedServiceId(9L); map.setState("active");
            instance.setId(20L); instance.setStartCount(1L);
            var expose = new ServiceExposeMapRecord(); expose.setServiceId(2L); expose.setInstanceId(20L); expose.setManaged(true);
            when(domain.objects.loadResource(Service.class, 2L)).thenReturn(service); when(domain.objects.loadResource(Service.class, 9L)).thenReturn(target);
            when(domain.objects.loadResource(Stack.class, 8L)).thenReturn(stack); when(domain.objects.loadResource(Instance.class, 20L)).thenReturn(instance);
            when(domain.objects.find(ServiceConsumeMap.class, "serviceId", 2L, "removed", null)).thenReturn(List.of(map));
            when(domain.objects.find(ServiceConsumeMap.class, "serviceId", 2L)).thenReturn(List.of(map));
            when(domain.objects.find(ServiceExposeMap.class, "serviceId", 2L, "removed", null)).thenReturn(List.of(expose));
            when(domain.targets.parseScopeId("service", "1s2")).thenReturn(2L); when(domain.targets.parseScopeId("service", "1s9")).thenReturn(9L);
        }
        boolean complete(Map<String, Object> before) { return domain.complete(descriptor, service, Map.of("serviceIds", List.of("1s9")), before); }
    }
}
