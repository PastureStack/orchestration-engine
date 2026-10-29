package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;

import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AiCatalogLaunchOwnerContractTest {
    @Test public void fixedCatalogOwnerRequestsNeverForwardCredentialsOrExposePrivateUpstreamErrors() throws Exception {
        JacksonJsonMapper json = new JacksonJsonMapper(); List<HttpRequest> calls = new ArrayList<>();
        String template = "ctm-" + "a".repeat(64), version = "cvm-" + "b".repeat(64);
        var gateway = new AiCatalogLaunchGateway(request -> {
            calls.add(request);
            return new AiCatalogMetadataGateway.Reply(200, json.writeValueAsString(Map.of("templateId", template,
                    "versionId", version, "digest", "d".repeat(64))).getBytes(StandardCharsets.UTF_8));
        }); gateway.json = json;
        gateway.material("1a5", template, version); gateway.validate("1a5", Map.of("humanAnswers", Map.of("password", "synthetic-private")));
        assertEquals("http://127.0.0.1:8088/v1-catalog/ai/launch-material?templateId=" + template + "&versionId=" + version, calls.getFirst().uri().toString());
        assertEquals("http://127.0.0.1:8088/v1-catalog/ai/launch-validate", calls.getLast().uri().toString());
        assertEquals("POST", calls.getLast().method());
        for (HttpRequest call : calls) {
            assertEquals("1a5", call.headers().firstValue("X-API-Project-Id").orElseThrow());
            assertTrue(Set.of("accept", "x-api-project-id",
                    "content-type").containsAll(call.headers().map().keySet().stream().map(String::toLowerCase).toList()));
            for (String header : List.of("Cookie", "Authorization", "X-PastureStack-Catalog-Launch-Delegation"))
                    assertTrue(call.headers().firstValue(header).isEmpty());
        }
        for (int status : List.of(301, 500, 422)) {
            var denied = new AiCatalogLaunchGateway(request -> new AiCatalogMetadataGateway.Reply(status,
                    "{\"code\":\"synthetic-private-owner-error\",\"message\":\"synthetic-private\"}".getBytes(StandardCharsets.UTF_8))); denied.json = json;
            var error = assertThrows(ClientVisibleException.class, () -> denied.material("1a5", template, version));
            assertFalse(error.toString().contains("synthetic-private")); assertNull(error.getCause());
        }
        assertThrows(ClientVisibleException.class, () -> gateway.material("http://foreign", template, version)); assertEquals(2, calls.size());
    }

    @Test public void ownerIpcRequiresExactPhaseProtocolAndBoundsFailuresNeverEchoPrivateProcessOutput() throws Exception {
        JacksonJsonMapper json = new JacksonJsonMapper(); AtomicInteger executions = new AtomicInteger();
        var runner = new AiCatalogLaunchRunner(input -> { executions.incrementAndGet(); return
                "{\"phase\":\"inspect\",\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8); });
        runner.json = json; assertEquals("inspect", runner.run(Map.of()).get("phase"));
        assertThrows(ClientVisibleException.class, () -> runner.run(Map.of("dockerCompose", "x".repeat(1536 * 1024)))); assertEquals(1, executions.get());
        for (String output : List.of("{\"phase\":\"inspect\",\"formatVersion\":1.5}",
                "{\"phase\":\"finalize\",\"formatVersion\":1}", "synthetic-private-invalid-json")) {
            var invalid = new AiCatalogLaunchRunner(input -> output.getBytes(StandardCharsets.UTF_8)); invalid.json = json;
            var error = assertThrows(ClientVisibleException.class, () -> invalid.run(Map.of())); assertEquals("CatalogLaunchUnavailable", error.getCode());
            assertFalse(error.toString().contains("synthetic-private")); assertNull(error.getCause());
        }
        var oversized = new AiCatalogLaunchRunner(input -> new byte[AiCatalogLaunchRunner.OUTPUT_BYTES + 1]); oversized.json = json;
        assertEquals("CatalogLaunchUnavailable", assertThrows(ClientVisibleException.class, () -> oversized.run(Map.of())).getCode());
    }

    @Test public void exactOwnerGraphRejectsCrossGraphReferencesExternalVolumesAndMissingLinksWithoutReplacingWorkloadLiterals() {
        AiCatalogLaunchGraph graph = new AiCatalogLaunchGraph(); graph.json = new JacksonJsonMapper();
        Map<String, Object> body = Map.of("name", "app", "stackId", "$createdStack", "launchConfig", Map.of("environment", Map.of("LITERAL", "$createdStack")));
        Map<String, Object> service = new LinkedHashMap<>(Map.of("name", "app", "resourceType", "service", "createBody", body,
                "setServiceLinks", Map.of("serviceLinks", List.of())));
        Map<String, Object> output = new LinkedHashMap<>(Map.of("phase", "finalize", "serviceRequests", List.of(service), "volumeRequests", List.of(),
                "services", List.of(), "dockerCompose", "# notemplating\nservices: {}", "rancherCompose", "", "variables", Map.of()));
        assertEquals(1, ((List<?>) graph.accept(output).get("services")).size());
        Map<String, Object> bound = AiCatalogLaunchInput.map(graph.bind(body, "1st889")); assertEquals("1st889", bound.get("stackId"));
        assertEquals("$createdStack", AiCatalogLaunchInput.map(AiCatalogLaunchInput.map(bound.get("launchConfig")).get("environment")).get("LITERAL"));
        assertEquals("$createdStack", body.get("stackId"));
        service.remove("setServiceLinks"); assertThrows(ClientVisibleException.class, () -> graph.accept(output));
        service.put("setServiceLinks", Map.of("serviceLinks", List.of(Map.of("serviceId", "$service:foreign"))));
        assertThrows(ClientVisibleException.class, () -> graph.accept(output));
        service.put("setServiceLinks", Map.of("serviceLinks", List.of(Map.of("serviceId", "$service:app"), Map.of("serviceId", "$service:app"))));
        assertThrows(ClientVisibleException.class, () -> graph.accept(output)); service.put("setServiceLinks", Map.of("serviceLinks", List.of()));
        output.put("volumeRequests", List.of(Map.of("name", "data", "resourceType", "volumeTemplate", "createSkeleton",
                Map.of("name", "data", "stackId", "$createdStack", "external", true))));
        assertEquals("CatalogLaunchUnsupported", assertThrows(ClientVisibleException.class, () -> graph.accept(output)).getCode());
        output.put("volumeRequests", List.of()); output.put("networks", Map.of("foreign", Map.of()));
        assertEquals("CatalogLaunchUnsupported", assertThrows(ClientVisibleException.class, () -> graph.accept(output)).getCode());
    }
}
