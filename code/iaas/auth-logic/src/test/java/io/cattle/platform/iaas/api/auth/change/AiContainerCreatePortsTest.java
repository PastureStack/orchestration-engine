package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.iaas.api.filter.instance.InstancePortsValidationFilter;
import io.cattle.platform.iaas.api.port.PortPreflightDao;
import io.cattle.platform.iaas.api.port.PortPreflightService;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Uses the normal Instance parser/preflight; a preview must never call manager.create. */
public class AiContainerCreatePortsTest {
    private Object enabled;
    @Before public void enable() {
        enabled = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
    }
    @After public void cleanup() {
        ApiContext.remove();
        if (enabled == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", enabled);
    }

    @Test public void sharedNativePortBoundaryKeepsFullCreateFieldsAndRejectsUnrelatedConfiguration() {
        Fixture f = new Fixture(); var parameters = f.parameters(List.of("18080:80/tcp", "53/udp", "[::1]:18081:81"));
        Map<String, Object> body = f.descriptor.body(parameters);
        assertEquals("container", body.get("type")); assertEquals("docker:busybox:1.37.0", body.get("imageUuid"));
        assertEquals("probe", body.get("name")); assertEquals(" exact description ", body.get("description"));
        assertEquals(List.of("sh", "-c", "echo ready"), body.get("command")); assertEquals(List.of("/bin/sh"), body.get("entryPoint"));
        assertEquals(parameters.get("ports"), body.get("ports")); assertEquals(false, body.get("startOnCreate"));
        assertEquals("bridge", body.get("networkMode")); assertFalse(body.containsKey("launchConfig"));
        for (Object ports : List.of("80", List.of(80), List.of(""), List.of("x".repeat(129)), java.util.Collections.nCopies(65, "80")))
            assertThrows(ClientVisibleException.class, () -> f.descriptor.parameters(Map.of("name", "probe", "image", "busybox", "ports", ports)));
        for (String field : List.of("launchConfig", "healthCheck", "stackId", "scale", "environment", "requestedHostId"))
            assertThrows(ClientVisibleException.class, () -> f.descriptor.parameters(Map.of("name", "probe", "image", "busybox", field, Map.of())));
        assertEquals(List.of(), AiCreatePorts.parameters(List.of()));
        assertEquals("PortInvalidPrivatePort", assertThrows(ClientVisibleException.class, () -> AiCreatePorts.parameters(List.of("18080:0"))).getCode());
    }

    @Test public void realNativePreflightPreservesNameImageAndArgumentsWithoutCreateOrPortAllocation() throws Exception {
        Fixture f = new Fixture(); var parameters = f.parameters(List.of("80/tcp"));
        Map<String, Object> body = f.domain.prepare(f.request, f.descriptor, null, parameters);
        var expected = new LinkedHashMap<>(f.descriptor.body(parameters)); expected.remove("type"); // Native ValidationHandler removes the envelope type.
        assertEquals(expected, body);
        verify(f.inventory).getEligibleHosts(77L); verify(f.inventory).getPortOwners(77L); verifyNoInteractions(f.manager);
        var created = new InstanceRecord(); created.setId(91L);
        when(f.manager.create(eq("container"), any())).thenAnswer(call -> {
            var request = (io.github.ibuildthecloud.gdapi.request.ApiRequest) call.getArgument(1);
            assertTrue(AiResourceChangeDomain.trustedDomain(request)); assertEquals(body, request.getRequestObject()); return created;
        });
        assertSame(created, f.domain.dispatch(f.request, f.descriptor, null, parameters, "own-plan"));
        verify(f.manager, times(1)).create(eq("container"), any()); assertSame(f.request, ApiContext.getContext().getApiRequest());
    }

    @Test public void nativeRoleFieldAndKeyDenialsCannotDropPortsOrUseAnotherWriteRoute() {
        Fixture f = new Fixture(); var parameters = f.parameters(List.of("18080:80"));
        f.fields.get("ports").setCreate(false);
        assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, f.descriptor, null, parameters)).getCode());
        f.fields.get("ports").setCreate(true); when(f.schema.getCollectionMethods()).thenReturn(List.of());
        assertEquals(405, assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, f.descriptor, null, parameters)).getStatus());
        when(f.schema.getCollectionMethods()).thenReturn(List.of("POST"));
        doThrow(new ClientVisibleException(403, "ApiKeyScopeDenied")).when(f.domain.keyAccess).authorize(any(), any());
        assertEquals("ApiKeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, f.descriptor, null, parameters)).getCode());
        verifyNoInteractions(f.manager, f.inventory);
    }

    @Test public void nativeDuplicateConflictAndLostAvailabilityRejectBeforeClaimAtConfirmationAndApply() throws Exception {
        Fixture f = new Fixture(); var duplicate = f.parameters(List.of("18080:80", "18080:81"));
        assertEquals("PortConflict", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, f.descriptor, null, duplicate)).getApiError().getCode());
        var available = f.inventory.getEligibleHosts(77L); var flow = f.workflow(List.of("18080:80"));
        when(f.inventory.getEligibleHosts(77L)).thenReturn(List.of());
        assertEquals("PortConflict", assertThrows(ClientVisibleException.class, flow::confirm).getCode());
        assertEquals("pending", flow.documents.get(flow.id).get("confirmationState"));
        when(f.inventory.getEligibleHosts(77L)).thenReturn(available); flow.confirm();
        when(f.inventory.getEligibleHosts(77L)).thenReturn(List.of());
        assertEquals("PortConflict", assertThrows(ClientVisibleException.class, () -> flow.apply(false)).getCode());
        assertEquals(0, flow.claims); assertEquals(0, flow.dispatched); verifyNoInteractions(f.manager);
    }

    @Test public void exactPortsReviewNeedsHumanConfirmationAndTheExistingDurableClaimRemainsIdempotent() throws Exception {
        Fixture f = new Fixture(); var ports = List.of("80/tcp", "127.0.0.1:18080:80/udp"); var flow = f.workflow(ports);
        var plan = flow.documents.get(flow.id);
        var changes = (List<Map<String, Object>>) flow.service.get(flow.id, flow.request(null, false)).get("changes");
        var diff = changes.stream().filter(change -> "ports".equals(change.get("field"))).findFirst().orElseThrow();
        assertNull(diff.get("before")); assertEquals("[\"80/tcp\",\"127.0.0.1:18080:80/udp\"]", diff.get("after"));
        assertEquals(0, flow.claims); assertEquals(0, flow.dispatched); assertFalse(plan.containsKey("executionResult"));
        assertEquals("ResourceChangeNeedsConfirmation", assertThrows(ClientVisibleException.class, () -> flow.apply(false)).getCode());
        flow.confirm(); flow.apply(false); flow.apply(false);
        assertEquals(1, flow.claims); assertEquals(1, flow.dispatched); verifyNoInteractions(f.manager);
    }

    @Test public void stoppedContainerReadbackVerifiesConfigurationAndNeverClaimsServiceDynamicAllocation() {
        Fixture f = new Fixture(); var parameters = f.parameters(List.of("80/tcp", "18081:81", "[::1]:53/udp"));
        var instance = new InstanceRecord(); instance.setState("stopped"); instance.setKind("container");
        DataAccessor.fields(instance).withKey("ports").set(List.of("80/tcp", "18081:81/tcp", "[::1]:53/udp"));
        assertTrue(f.domain.complete(f.descriptor, instance, parameters, Map.of()));
        assertFalse(AiCreatePorts.assignedMatch(List.of("80/tcp"), List.of("80/tcp")));
        assertTrue(AiCreatePorts.assignedMatch(List.of("80/tcp"), List.of("32768:80/tcp")));
        instance.setState("running"); assertFalse(f.domain.complete(f.descriptor, instance, parameters, Map.of()));
        instance.setState("stopped");
        for (List<String> actual : List.of(List.of("81/tcp", "18081:81/tcp", "[::1]:53/udp"),
                List.of("80/tcp", "18082:81/tcp", "[::1]:53/udp"), List.of("80/tcp", "18081:81/tcp", "[::2]:53/udp"),
                List.of("80/tcp"), List.of("bad"), List.of("80/tcp", "18081:81/tcp", "[::1]:53/udp", "90/tcp"))) {
            DataAccessor.fields(instance).withKey("ports").set(actual);
            assertFalse(actual.toString(), f.domain.complete(f.descriptor, instance, parameters, Map.of()));
        }
    }

    @Test public void ordinaryNativeFilterKeepsTheOriginalRequestAndStillParsesBeforeDispatch() {
        Fixture f = new Fixture(); f.request.setType("container");
        f.request.setRequestObject(f.descriptor.body(f.parameters(List.of("18080:80/tcp"))));
        var created = new InstanceRecord(); when(f.manager.create("container", f.request)).thenReturn(created);
        assertSame(created, f.nativeFilter.create("container", f.request, f.manager));
        verify(f.manager, times(1)).create("container", f.request);
        f.request.setRequestObject(f.descriptor.body(f.parameters(List.of("18080:80/tcp", "18080:81/tcp"))));
        assertEquals("PortConflict", assertThrows(ClientVisibleException.class,
                () -> f.nativeFilter.create("container", f.request, f.manager)).getApiError().getCode());
        verify(f.manager, times(1)).create("container", f.request);
    }

    private static class Fixture extends AiResourceChangeDomainTest.Fixture {
        final AiResourceChangeDescriptor descriptor = AiResourceChangeDescriptor.require("container", "create");
        final PortPreflightDao inventory = mock(PortPreflightDao.class);
        final ResourceManager manager = mock(ResourceManager.class);
        final InstancePortsValidationFilter nativeFilter = new InstancePortsValidationFilter();
        final Map<String, FieldImpl> fields = new LinkedHashMap<>();
        Fixture() {
            when(schemas.getSchema("container")).thenReturn(schema); when(schema.getCollectionMethods()).thenReturn(List.of("POST"));
            for (String field : List.of("name", "description", "imageUuid", "networkMode", "command", "entryPoint", "ports", "startOnCreate")) {
                var value = new FieldImpl(); value.setType("startOnCreate".equals(field) ? "boolean"
                        : List.of("ports", "command", "entryPoint").contains(field) ? "array[string]" : "string");
                value.setCreate(true); fields.put(field, value);
            }
            when(schema.getResourceFields()).thenReturn((Map<String, Field>) (Map) fields);
            var validator = new ValidationHandler(); validator.init(); domain.validation = validator;
            var host = new HostRecord(); host.setId(1L); host.setAccountId(77L);
            when(inventory.getEligibleHosts(77L)).thenReturn(List.of(host)); when(inventory.getPortOwners(77L)).thenReturn(List.of());
            var preflight = new PortPreflightService(); inject(preflight, "dao", inventory); inject(preflight, "objectManager", domain.objects);
            inject(nativeFilter, "portPreflightService", preflight);
            inject(nativeFilter, "objectManager", domain.objects); domain.instanceCreatePortValidation = nativeFilter;
            domain.keyAccess = spy(domain.keyAccess);
            domain.managers = mock(ResourceManagerLocator.class); when(domain.managers.getResourceManagerByType("container")).thenReturn(manager);
        }
        Map<String, Object> parameters(List<String> ports) {
            return descriptor.parameters(Map.of("name", "probe", "image", "busybox:1.37.0", "description", " exact description ",
                    "command", List.of("sh", "-c", "echo ready"), "entryPoint", List.of("/bin/sh"), "ports", ports));
        }
        AiResourceChangePlanServiceTest.Fixture workflow(List<String> ports) {
            var flow = new AiResourceChangePlanServiceTest.Fixture(); flow.create = true;
            when(flow.domain.prepare(any(), any(), isNull(), anyMap())).thenAnswer(call -> domain.prepare(request, descriptor, null, call.getArgument(3)));
            when(flow.domain.prepare(any(), any(), isNull(), anyMap(), anyMap())).thenAnswer(call ->
                    domain.prepare(request, descriptor, null, call.getArgument(3), call.getArgument(4)));
            when(flow.domain.changes(any(), any(), anyMap())).thenAnswer(call -> domain.changes(descriptor, null, call.getArgument(2)));
            var preview = flow.request(null, false); preview.setId(null);
            preview.setRequestObject(Map.of("resourceType", "container", "operation", "create", "parameters", parameters(ports)));
            flow.id = (String) flow.service.preview(preview).get("id"); return flow;
        }
        private static void inject(Object target, String field, Object value) {
            try { var injection = target.getClass().getDeclaredField(field); injection.setAccessible(true); injection.set(target, value); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
    }
}
