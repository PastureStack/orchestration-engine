package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.util.PortSpec;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.impl.JooqObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.servicediscovery.api.filter.ServiceValidationFilter;
import io.cattle.platform.storage.service.StorageService;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiServiceCreateChangeTest {
    @Test public void nativeObjectMappingKeepsStartOnCreateInThePersistedServiceDataField() {
        var schema = new SchemaImpl(); schema.setResourceFields(Map.of("startOnCreate", new FieldImpl()));
        var service = new ServiceRecord();
        var nativeObjects = new JooqObjectManager() {
            void mapCreateField() { setField(schema, service, "startOnCreate", false); }
        };
        nativeObjects.mapCreateField();
        assertEquals(false, DataAccessor.field(service, "startOnCreate", Object.class));
        assertEquals(false, ((Map<?, ?>) service.getData().get("fields")).get("startOnCreate"));
    }

    @Test public void actualNativeReadbackAcceptsAllocatedPortsAndNativeNormalizationWithoutUsingTheRequestBodyAsEvidence() throws Exception {
        Fixture f = new Fixture();
        assertTrue(f.complete());
        var assigned = f.domain.serviceCreateResult(f.service);
        assertEquals(List.of("18080:80/tcp", "127.0.0.1:31001:80/tcp", "31002:80/udp"),
                assigned.get("assignedPorts"));
        // The approved input remains the request for native allocation, rather than the observed assigned values.
        assertEquals(List.of("127.0.0.1::80/tcp", "18080:080", "80/udp"), f.parameters.get("ports"));
    }

    @Test public void anyDifferentPrivateProtocolIpFixedPublicMissingOrDuplicateMappingCannotComplete() throws Exception {
        for (List<String> ports : List.of(
                List.of("18080:81/tcp", "127.0.0.1:31001:80/tcp", "31002:80/udp"),
                List.of("18080:80/udp", "127.0.0.1:31001:80/tcp", "31002:80/udp"),
                List.of("18080:80/tcp", "127.0.0.2:31001:80/tcp", "31002:80/udp"),
                List.of("18081:80/tcp", "127.0.0.1:31001:80/tcp", "31002:80/udp"),
                List.of("18080:80/tcp", "127.0.0.1:31001:80/tcp"),
                List.of("18080:80/tcp", "127.0.0.1:31001:80/tcp", "127.0.0.1:31001:80/tcp"),
                List.of("18080:80/tcp", "127.0.0.1:80/tcp", "31002:80/udp"),
                List.of("18080:80/tcp", "127.0.0.1:0:80/tcp", "31002:80/udp"))) {
            Fixture f = new Fixture(); f.launch.put("ports", ports); assertFalse(ports.toString(), f.complete());
        }
        Fixture missing = new Fixture(); missing.launch.remove("ports"); assertFalse(missing.complete());
        Fixture extra = new Fixture();
        List<String> ports = new ArrayList<>((List<String>) extra.launch.get("ports")); ports.add("32000:9000/tcp");
        extra.launch.put("ports", ports); assertFalse(extra.complete());
    }

    @Test public void fixedMappingsAreMatchedBeforeRandomRequestsForTheSamePrivatePort() throws Exception {
        Fixture f = new Fixture();
        f.parameters.put("ports", List.of("80", "18080:80"));
        f.launch.put("ports", List.of("18080:80/tcp", "31001:80/tcp")); assertTrue(f.complete());
        f.launch.put("ports", List.of("31001:80/tcp", "31002:80/tcp")); assertFalse(f.complete());
        f.parameters.put("ports", List.of("80", "80"));
        f.launch.put("ports", List.of("31001:80/tcp")); assertFalse(f.complete());
        f.launch.put("ports", List.of("31001:80/tcp", "31001:80/tcp")); assertFalse(f.complete());
        f.launch.put("ports", List.of("31001:80/tcp", "31002:80/tcp")); assertTrue(f.complete());
    }

    @Test public void ipv6ReadbackUsesTheNativeBracketedFixedAndAllocatedPortRepresentation() throws Exception {
        Fixture f = new Fixture();
        f.parameters.put("ports", List.of("[::1]:18080:80", "[2001:db8::1]:53/udp"));
        PortSpec fixed = new PortSpec("[::1]:18080:80");
        PortSpec allocated = new PortSpec("[2001:db8::1]:53/udp"); allocated.setPublicPort(31001);
        f.launch.put("ports", List.of(fixed.toSpec(), allocated.toSpec()));
        assertEquals(List.of("[::1]:18080:80/tcp", "[2001:db8::1]:31001:53/udp"), f.launch.get("ports"));
        assertTrue(f.complete());
        f.launch.put("ports", List.of("[::1]:18080:80/tcp", "[2001:db8::2]:31001:53/udp")); assertFalse(f.complete());
    }

    @Test public void healthThresholdPortRequestStrategyAndUnexpectedConfigurationMustMatch() throws Exception {
        for (var entry : Map.<String, Object>of("port", 81, "healthyThreshold", 3, "interval", 3000,
                "requestLine", "GET /changed HTTP/1.0", "strategy", "none").entrySet()) {
            Fixture f = new Fixture(); f.health.put(entry.getKey(), entry.getValue()); assertFalse(entry.getKey(), f.complete());
        }
        Fixture missing = new Fixture(); missing.launch.remove("healthCheck"); assertFalse(missing.complete());
        Fixture unexpected = new Fixture(); unexpected.health.put("recreateOnQuorumStrategyConfig", Map.of("quorum", 1));
        assertFalse(unexpected.complete());
        Fixture nativeDefault = new Fixture(); nativeDefault.health.remove("strategy"); assertTrue(nativeDefault.complete());
    }

    @Test public void nativeInactiveStateAloneCannotProveImageParentProgramsOrMetadataWereCreatedCorrectly() throws Exception {
        Fixture image = new Fixture(); image.launch.put("imageUuid", "docker:registry.test/team/other:1"); assertFalse(image.complete());
        Fixture stack = new Fixture(); stack.service.setStackId(4L); assertFalse(stack.complete());
        Fixture command = new Fixture(); command.launch.put("command", List.of("wrong")); assertFalse(command.complete());
        Fixture entryPoint = new Fixture(); entryPoint.launch.put("entryPoint", List.of("/wrong")); assertFalse(entryPoint.complete());
        Fixture name = new Fixture(); name.service.setName("other"); assertFalse(name.complete());
        Fixture description = new Fixture(); description.service.setDescription("other"); assertFalse(description.complete());
        Fixture network = new Fixture(); network.launch.put("networkMode", "host"); assertFalse(network.complete());
        Fixture scale = new Fixture(); scale.fields.put("scale", 2L); assertFalse(scale.complete());
        Fixture started = new Fixture(); started.fields.put("startOnCreate", true); assertFalse(started.complete());
        Fixture active = new Fixture(); active.service.setState("active"); assertFalse(active.complete());
    }

    private static class Fixture {
        final AiResourceChangeDomain domain = new AiResourceChangeDomain();
        final AiResourceChangeDescriptor descriptor = AiResourceChangeDescriptor.require("service", "create");
        final ServiceRecord service = new ServiceRecord();
        final Map<String, Object> fields = new LinkedHashMap<>(), launch = new LinkedHashMap<>(), health = new LinkedHashMap<>();
        final Map<String, Object> parameters;
        Fixture() throws Exception {
            parameters = new LinkedHashMap<>(descriptor.parameters(Map.of("name", "probe", "image", "registry.test/team/app:1", "stackId", "1st3",
                    "command", List.of("serve", "--foreground"), "entryPoint", List.of("/app"),
                    "ports", List.of("127.0.0.1::80/tcp", "18080:080", "80/udp"),
                    "healthCheck", Map.of("port", 80, "interval", 2000, "healthyThreshold", 2, "requestLine", "GET / HTTP/1.0"))));
            // A native record independently represents the persisted output of ServiceCreate.setPorts:
            // fixed mapping first (PortSpec.toSpec), then the allocated public ports, preserving private/protocol/IP.
            service.setId(91L); service.setUuid("created-service-uuid"); service.setAccountId(77L); service.setKind("service");
            service.setName("probe"); service.setStackId(3L); service.setState("inactive");
            launch.put("imageUuid", "docker:registry.test/team/app:1"); launch.put("networkMode", "bridge");
            launch.put("command", List.of("serve", "--foreground")); launch.put("entryPoint", List.of("/app"));
            launch.put("ports", List.of("18080:80/tcp", "127.0.0.1:31001:80/tcp", "31002:80/udp"));
            health.put("port", 80L); health.put("interval", 2000L); health.put("healthyThreshold", 2L);
            health.put("requestLine", "GET / HTTP/1.0"); health.put("strategy", "recreate"); launch.put("healthCheck", health);
            fields.put("scale", 1L); fields.put("startOnCreate", false); fields.put("launchConfig", launch);
            service.setData(Map.of("fields", fields));
            domain.targets = mock(ApiKeyTargetResolver.class); when(domain.targets.parseScopeId("stack", "1st3")).thenReturn(3L);
            var nativeFilter = new ServiceValidationFilter(); StorageService storage = mock(StorageService.class);
            when(storage.isValidUUID(anyString())).thenReturn(true);
            var injection = ServiceValidationFilter.class.getDeclaredField("storageService"); injection.setAccessible(true); injection.set(nativeFilter, storage);
            domain.serviceCreateValidation = nativeFilter; domain.json = new JacksonJsonMapper();
        }
        boolean complete() { return domain.complete(descriptor, service, parameters, Map.of()); }
    }
}
