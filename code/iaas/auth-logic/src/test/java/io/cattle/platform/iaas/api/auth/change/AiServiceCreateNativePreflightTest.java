package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.iaas.api.port.PortPreflightDao;
import io.cattle.platform.iaas.api.port.PortPreflightService;
import io.cattle.platform.servicediscovery.api.filter.ServiceValidationFilter;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;
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

/** The real schema validator and the same native filter/parser/preflight used by manager.create. */
public class AiServiceCreateNativePreflightTest {
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

    @Test public void realNativeBodyUsesConvertedParentAndHealthFieldsAndDispatchStillUsesTheFilteredManager() throws Exception {
        Fixture f = new Fixture();
        var parameters = f.parameters(List.of("80/tcp"));
        var body = f.domain.prepare(f.request, f.descriptor, null, parameters);
        assertEquals(3, ((Number) body.get("stackId")).intValue());
        assertEquals(false, body.get("startOnCreate")); assertEquals(1, ((Number) body.get("scale")).intValue());
        Map<?, ?> launch = (Map<?, ?>) body.get("launchConfig");
        assertEquals("bridge", launch.get("networkMode")); assertEquals("docker:busybox:1.37.0", launch.get("imageUuid"));
        assertEquals(List.of("80/tcp"), launch.get("ports"));
        assertEquals(Map.of("port", 80, "interval", 2000, "strategy", "recreate"), launch.get("healthCheck"));
        assertFalse(body.containsKey("ports")); assertFalse(body.containsKey("healthCheck"));
        verify(f.nativeInventory).getEligibleHosts(77L); verify(f.nativeInventory).getPortOwners(77L);
        verifyNoInteractions(f.manager);
        ServiceRecord created = new ServiceRecord(); created.setId(91L);
        when(f.manager.create(eq("service"), any())).thenReturn(created);
        assertSame(created, f.domain.dispatch(f.request, f.descriptor, null, parameters, "plan"));
        verify(f.manager, times(1)).create(eq("service"), any());
        assertSame(f.request, ApiContext.getContext().getApiRequest());
    }

    @Test public void realNativeDuplicatePortConflictFailsBeforeAnyManagerCreateAndParserCodesRemainNative() throws Exception {
        Fixture f = new Fixture();
        var denied = assertThrows(ClientVisibleException.class, () -> f.domain.prepare(f.request, f.descriptor, null,
                f.parameters(List.of("18080:80/tcp", "18080:81/tcp"))));
        assertEquals("PortConflict", denied.getApiError().getCode()); assertEquals(422, denied.getApiError().getStatus());
        var malformed = new LinkedHashMap<>(f.parameters(List.of("80"))); malformed.put("ports", List.of("18080:0"));
        var parser = assertThrows(ClientVisibleException.class, () -> f.domain.prepare(f.request, f.descriptor, null, malformed));
        assertEquals("PortInvalidPrivatePort", parser.getCode()); verifyNoInteractions(f.manager);
        assertSame(f.request, ApiContext.getContext().getApiRequest());
    }

    @Test public void nativeNestedRoleFieldRemovalCannotSilentlyTurnApprovedHealthCheckIntoAnotherConfiguration() throws Exception {
        Fixture f = new Fixture(); f.healthFields.get("interval").setCreate(false);
        var denied = assertThrows(ClientVisibleException.class, () -> f.domain.prepare(f.request, f.descriptor, null, f.parameters(List.of("80"))));
        assertEquals("PermissionDenied", denied.getCode()); verifyNoInteractions(f.manager, f.nativeInventory);
    }

    @Test public void liveNativePortAvailabilityIsRepeatedAtConfirmationAndImmediatelyBeforeTheDurableClaim() throws Exception {
        Fixture nativeDomain = new Fixture(); var available = nativeDomain.nativeInventory.getEligibleHosts(77L);
        var formatter = ApiContext.getContext().getIdFormatter();
        var workflow = new AiResourceChangePlanServiceTest.Fixture();
        ApiContext.getContext().setIdFormatter(formatter);
        when(workflow.domain.prepare(any(), any(), isNull(), anyMap())).thenAnswer(call ->
                nativeDomain.domain.prepare(nativeDomain.request, call.getArgument(1), null, call.getArgument(3)));
        when(workflow.domain.prepare(any(), any(), isNull(), anyMap(), anyMap())).thenAnswer(call ->
                nativeDomain.domain.prepare(nativeDomain.request, call.getArgument(1), null, call.getArgument(3), call.getArgument(4)));
        workflow.previewServiceCreate(false, List.of("18080:80/tcp"));
        when(nativeDomain.nativeInventory.getEligibleHosts(77L)).thenReturn(List.of());
        var confirm = assertThrows(ClientVisibleException.class, workflow::confirm);
        assertEquals("PortConflict", confirm.getCode()); assertEquals(422, confirm.getStatus());
        assertEquals("pending", workflow.documents.get(workflow.id).get("confirmationState"));
        when(nativeDomain.nativeInventory.getEligibleHosts(77L)).thenReturn(available); workflow.confirm();
        when(nativeDomain.nativeInventory.getEligibleHosts(77L)).thenReturn(List.of());
        var apply = assertThrows(ClientVisibleException.class, () -> workflow.apply(false));
        assertEquals("PortConflict", apply.getCode()); assertEquals(422, apply.getStatus());
        assertEquals("preview", workflow.documents.get(workflow.id).get("status"));
        when(nativeDomain.nativeInventory.getEligibleHosts(77L)).thenReturn(available);
        doThrow(new ClientVisibleException(403, "ApiKeyRevoked")).when(nativeDomain.domain.keys).authorizeBoundDomain(anyMap(), any());
        assertEquals("ApiKeyRevoked", assertThrows(ClientVisibleException.class, () -> workflow.apply(false)).getCode());
        assertEquals(0, workflow.claims); assertEquals(0, workflow.dispatched); verifyNoInteractions(nativeDomain.manager);
    }

    private static class Fixture extends AiResourceChangeDomainTest.Fixture {
        final AiResourceChangeDescriptor descriptor = AiResourceChangeDescriptor.require("service", "create");
        final PortPreflightDao nativeInventory = mock(PortPreflightDao.class);
        final ResourceManager manager = mock(ResourceManager.class);
        final Map<String, FieldImpl> healthFields = new LinkedHashMap<>();
        Fixture() throws Exception {
            when(schema.getCollectionMethods()).thenReturn(List.of("POST"));
            Map<String, Field> fields = new LinkedHashMap<>();
            for (String field : List.of("name", "description", "scale", "stackId", "launchConfig", "startOnCreate"))
                fields.put(field, field(switch (field) { case "scale" -> "int"; case "stackId" -> "reference[stack]";
                    case "launchConfig" -> "container"; case "startOnCreate" -> "boolean"; default -> "string"; }));
            when(schema.getResourceFields()).thenReturn(fields);
            Schema launch = mock(Schema.class); Map<String, Field> launchFields = new LinkedHashMap<>();
            for (String field : List.of("imageUuid", "networkMode", "ports", "healthCheck"))
                launchFields.put(field, field("ports".equals(field) ? "array[string]" : "healthCheck".equals(field) ? "instanceHealthCheck" : "string"));
            when(launch.getResourceFields()).thenReturn(launchFields); when(schemas.getSchema("container")).thenReturn(launch);
            Schema health = mock(Schema.class);
            for (String field : List.of("port", "interval", "strategy")) healthFields.put(field, field("strategy".equals(field) ? "string" : "int"));
            when(health.getResourceFields()).thenReturn((Map) healthFields); when(schemas.getSchema("instanceHealthCheck")).thenReturn(health);
            var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas); formatter.setTypeMappings(Map.of("stack", "st"));
            ApiContext.getContext().setIdFormatter(formatter);
            var validator = new ValidationHandler(); validator.init(); domain.validation = validator;
            var stack = new StackRecord(); stack.setId(3L); stack.setAccountId(77L);
            when(domain.objects.loadResource(Stack.class, 3L)).thenReturn(stack);
            var host = new HostRecord(); host.setId(1L); host.setAccountId(77L);
            when(nativeInventory.getEligibleHosts(77L)).thenReturn(List.of(host)); when(nativeInventory.getPortOwners(77L)).thenReturn(List.of());
            var nativePreflight = new PortPreflightService(); inject(nativePreflight, "dao", nativeInventory); inject(nativePreflight, "objectManager", domain.objects);
            var nativeFilter = new ServiceValidationFilter(); inject(nativeFilter, "objectManager", domain.objects);
            inject(nativeFilter, "jsonMapper", domain.json); inject(nativeFilter, "portPreflightService", nativePreflight);
            domain.serviceCreateValidation = nativeFilter;
            domain.managers = mock(ResourceManagerLocator.class); when(domain.managers.getResourceManagerByType("service")).thenReturn(manager);
        }
        Map<String, Object> parameters(List<String> ports) {
            return descriptor.parameters(Map.of("name", "probe", "image", "busybox:1.37.0", "stackId", "1st3", "ports", ports,
                    "healthCheck", Map.of("port", 80, "interval", 2000)));
        }
        private static FieldImpl field(String type) { var result = new FieldImpl(); result.setType(type); result.setCreate(true); return result; }
        private static void inject(Object target, String field, Object value) throws Exception {
            var injection = target.getClass().getDeclaredField(field); injection.setAccessible(true); injection.set(target, value);
        }
    }
}
