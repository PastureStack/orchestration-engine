package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Uses the shipped schema validator, filtered-manager dispatch and durable plan JSON copy. */
public class AiExternalServiceTcpHealthCheckTest {
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

    @Test public void nativeSchemaKeepsTopLevelTcpFieldsAndNoneWithoutAddingTimingDefaults() throws Exception {
        Fixture f = new Fixture(); var parameters = f.parameters(Map.of("port", 80, "interval", Integer.MAX_VALUE));
        var body = f.domain.prepare(f.request, f.descriptor, null, parameters);
        assertEquals(3, ((Number) body.get("stackId")).intValue());
        Map<?, ?> health = (Map<?, ?>) body.get("healthCheck");
        assertEquals(80, health.get("port")); assertEquals(Integer.MAX_VALUE, health.get("interval"));
        assertNull(health.get("requestLine")); assertFalse(health.containsKey("responseTimeout"));
        assertFalse(((Map<?, ?>) body.get("launchConfig")).containsKey("healthCheck"));
        assertFalse(body.containsKey("imageUuid")); assertEquals(false, body.get("startOnCreate"));
        when(f.manager.create(eq("externalService"), any())).thenAnswer(call -> {
            ApiRequest actual = call.getArgument(1);
            assertEquals(body, f.domain.json.readValue(f.domain.json.writeValueAsString(actual.getRequestObject())));
            assertSame(actual, ApiContext.getContext().getApiRequest());
            return f.created;
        });
        assertSame(f.created, f.domain.dispatch(f.request, f.descriptor, null, parameters, "plan"));
        assertSame(f.request, ApiContext.getContext().getApiRequest());
        assertFalse(f.domain.prepare(f.request, f.descriptor, null, f.parameters(null)).containsKey("healthCheck"));
        verify(f.manager, times(1)).create(eq("externalService"), any());
    }

    @Test public void nativeRoleRemovalHttpFallbackAndBoundKeyRevocationFailBeforeMutation() throws Exception {
        Fixture f = new Fixture(); var parameters = f.parameters(Map.of("port", 80, "interval", 2000));
        f.rootHealth.setCreate(false);
        assertDenied(() -> f.domain.prepare(f.request, f.descriptor, null, parameters));
        f.rootHealth.setCreate(true); f.healthFields.get("interval").setCreate(false);
        assertDenied(() -> f.domain.prepare(f.request, f.descriptor, null, parameters));
        assertDenied(() -> f.domain.dispatch(f.request, f.descriptor, null, parameters, "plan"));
        f.healthFields.get("interval").setCreate(true);
        f.healthFields.get("requestLine").setDefault("GET / HTTP/1.0");
        assertNull(((Map<?, ?>) f.domain.prepare(f.request, f.descriptor, null, parameters).get("healthCheck")).get("requestLine"));
        f.healthFields.get("requestLine").setNullable(false);
        assertDenied(() -> f.domain.prepare(f.request, f.descriptor, null, parameters));
        assertDenied(() -> f.domain.dispatch(f.request, f.descriptor, null, parameters, "plan"));
        f.healthFields.get("requestLine").setDefault(null); f.healthFields.get("requestLine").setNullable(true);
        doThrow(new ClientVisibleException(403, "ApiKeyRevoked")).when(f.domain.keys).authorizeBoundDomain(anyMap(), any());
        assertEquals("ApiKeyRevoked", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, f.descriptor, null, parameters, Map.of())).getCode());
        verifyNoInteractions(f.manager);
    }

    @Test public void approvedTcpSurvivesJsonReloadHumanReviewNativeDispatchAndOneReceipt() throws Exception {
        Fixture nativeDomain = new Fixture(); var workflow = workflow(nativeDomain);
        var health = new LinkedHashMap<String, Object>(Map.of("port", 443L, "interval", 2000L));
        Map<String, Object> preview = preview(workflow, nativeDomain, health);
        List<?> changes = (List<?>) preview.get("changes");
        Map<?, ?> change = changes.stream().map(value -> (Map<?, ?>) value)
                .filter(row -> "healthCheck".equals(row.get("field"))).findFirst().orElseThrow();
        assertEquals(Map.of("port", 443, "interval", 2000), new JacksonJsonMapper().readValue((String) change.get("after")));
        assertFalse(change.toString().contains("requestLine")); assertFalse(change.toString().contains("strategy"));
        health.put("port", 80L);
        assertEquals("ResourceChangeNeedsConfirmation", assertThrows(ClientVisibleException.class, () -> workflow.apply(true)).getCode());
        assertThrows(ClientVisibleException.class, () -> workflow.service.action(workflow.id, workflow.request("confirm", true)));
        workflow.confirm();
        assertEquals("ResourceChangeBoundKeyRequired", assertThrows(ClientVisibleException.class, () -> workflow.apply(false)).getCode());
        var restarted = new AiResourceChangePlanService() { @Override protected void requireEnabled() { } };
        restarted.domain = workflow.domain; restarted.store = workflow.service.store; restarted.clock = workflow.service.clock;
        restarted.locks = workflow.service.locks; restarted.auditSinks = workflow.service.auditSinks;
        assertEquals("accepted", restarted.action(workflow.id, workflow.request("apply", true)).get("status"));
        assertEquals("succeeded", restarted.get(workflow.id, workflow.request(null, true)).get("status"));
        assertEquals("succeeded", restarted.action(workflow.id, workflow.request("apply", true)).get("status"));
        assertEquals(1, workflow.claims); assertEquals(1, workflow.dispatched);
        verify(nativeDomain.manager, times(1)).create(eq("externalService"), any());
        assertEquals(443L, ((Number) ((Map<?, ?>) DataAccessor.field(nativeDomain.created, "healthCheck", Object.class)).get("port")).longValue());
    }

    @Test public void changedApprovedTcpBodyAndCurrentRoleCannotReachTheDurableClaim() throws Exception {
        Fixture nativeDomain = new Fixture(); var workflow = workflow(nativeDomain);
        preview(workflow, nativeDomain, Map.of("port", 80, "interval", 2000)); workflow.confirm();
        Map<String, Object> plan = workflow.documents.get(workflow.id);
        Map<String, Object> body = (Map<String, Object>) plan.get("body");
        Map<String, Object> health = (Map<String, Object>) body.get("healthCheck"); health.put("port", 81);
        assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class, () -> workflow.apply(true)).getCode());
        health.put("port", 80); nativeDomain.healthFields.get("interval").setCreate(false);
        assertDenied(() -> workflow.apply(true));
        nativeDomain.healthFields.get("interval").setCreate(true);
        doThrow(new ClientVisibleException(403, "ApiKeyRevoked")).when(nativeDomain.domain.keys).authorizeBoundDomain(anyMap(), any());
        assertEquals("ApiKeyRevoked", assertThrows(ClientVisibleException.class, () -> workflow.apply(true)).getCode());
        assertEquals(0, workflow.claims); assertEquals(0, workflow.dispatched); verifyNoInteractions(nativeDomain.manager);
    }

    private static void assertDenied(Runnable operation) {
        assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class, operation::run).getCode());
    }

    private static AiResourceChangePlanServiceTest.Fixture workflow(Fixture nativeDomain) {
        var formatter = ApiContext.getContext().getIdFormatter();
        var workflow = new AiResourceChangePlanServiceTest.Fixture(); ApiContext.getContext().setIdFormatter(formatter);
        workflow.domain.json = workflow.json;
        when(workflow.domain.prepare(any(), any(), isNull(), anyMap())).thenAnswer(call ->
                nativeDomain.domain.prepare(nativeDomain.request, call.getArgument(1), null, call.getArgument(3)));
        when(workflow.domain.prepare(any(), any(), isNull(), anyMap(), anyMap())).thenAnswer(call ->
                nativeDomain.domain.prepare(nativeDomain.request, call.getArgument(1), null, call.getArgument(3), call.getArgument(4)));
        when(workflow.domain.changes(any(), any(), anyMap())).thenCallRealMethod();
        doAnswer(call -> {
            workflow.dispatched++;
            return nativeDomain.domain.dispatch(nativeDomain.request, call.getArgument(1), null, call.getArgument(3), call.getArgument(4));
        }).when(workflow.domain).dispatch(any(), any(), isNull(), anyMap(), anyString());
        when(workflow.domain.complete(any(), any(), anyMap(), anyMap())).thenAnswer(call ->
                AiExternalServiceChange.complete("create", nativeDomain.created, call.getArgument(2), List.of()));
        when(nativeDomain.manager.create(eq("externalService"), any())).thenAnswer(call -> {
            ApiRequest request = call.getArgument(1); var body = (Map<?, ?>) request.getRequestObject();
            DataAccessor.fields(nativeDomain.created).withKey("healthCheck").set(body.get("healthCheck"));
            return nativeDomain.created;
        });
        return workflow;
    }

    private static Map<String, Object> preview(AiResourceChangePlanServiceTest.Fixture workflow, Fixture nativeDomain, Object health) {
        ServiceRecord parent = new ServiceRecord(); parent.setId(3L); parent.setKind("stack"); parent.setName("Default"); parent.setState("active");
        when(workflow.domain.parent(any(), any(), anyMap())).thenReturn(parent); when(workflow.domain.lock(eq("stack"), anyLong())).thenReturn(parent);
        ApiRequest request = workflow.request(null, true); request.setId(null);
        request.setRequestObject(Map.of("resourceType", "externalService", "operation", "create", "parameters", nativeDomain.parameters(health)));
        var result = workflow.service.preview(request); workflow.id = (String) result.get("id"); return result;
    }

    private static class Fixture extends AiResourceChangeDomainTest.Fixture {
        final AiResourceChangeDescriptor descriptor = AiResourceChangeDescriptor.require("externalService", "create");
        final ResourceManager manager = mock(ResourceManager.class);
        final FieldImpl rootHealth = field("instanceHealthCheck");
        final Map<String, FieldImpl> healthFields = new LinkedHashMap<>();
        final ServiceRecord created = new ServiceRecord();
        Fixture() {
            when(schemas.getSchema("externalService")).thenReturn(schema); when(schema.getCollectionMethods()).thenReturn(List.of("POST"));
            Map<String, Field> fields = new LinkedHashMap<>();
            for (String name : List.of("name", "stackId", "hostname", "externalIpAddresses", "launchConfig", "startOnCreate"))
                fields.put(name, field(switch (name) { case "stackId" -> "reference[stack]"; case "externalIpAddresses" -> "array[string]";
                    case "launchConfig" -> "container"; case "startOnCreate" -> "boolean"; default -> "string"; }));
            fields.put("healthCheck", rootHealth); when(schema.getResourceFields()).thenReturn(fields);
            Schema launch = mock(Schema.class); when(launch.getResourceFields()).thenReturn(Map.of()); when(schemas.getSchema("container")).thenReturn(launch);
            Schema health = mock(Schema.class);
            for (String name : List.of("port", "interval", "responseTimeout", "healthyThreshold", "unhealthyThreshold", "requestLine", "strategy"))
                healthFields.put(name, field("requestLine".equals(name) || "strategy".equals(name) ? "string" : "int"));
            healthFields.get("port").setMin(1L); healthFields.get("port").setMax(65535L); healthFields.get("port").setRequired(true);
            healthFields.get("strategy").setDefault("recreate");
            when(health.getResourceFields()).thenReturn((Map) healthFields); when(schemas.getSchema("instanceHealthCheck")).thenReturn(health);
            var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas); formatter.setTypeMappings(Map.of("stack", "st"));
            ApiContext.getContext().setIdFormatter(formatter); var validator = new ValidationHandler(); validator.init(); domain.validation = validator;
            domain.managers = mock(ResourceManagerLocator.class); when(domain.managers.getResourceManagerByType("externalService")).thenReturn(manager);
            created.setId(91L); created.setKind("externalService"); created.setAccountId(77L); created.setStackId(3L); created.setState("inactive");
            DataAccessor.fields(created).withKey("hostname").set("db.test");
        }
        Map<String, Object> parameters(Object health) {
            var result = new LinkedHashMap<String, Object>(Map.of("name", "db", "stackId", "1st3", "hostname", "db.test"));
            if (health != null) result.put("healthCheck", health); return descriptor.parameters(result);
        }
        private static FieldImpl field(String type) {
            var result = new FieldImpl(); result.setType(type); result.setCreate(true); result.setNullable(true); return result;
        }
    }
}
