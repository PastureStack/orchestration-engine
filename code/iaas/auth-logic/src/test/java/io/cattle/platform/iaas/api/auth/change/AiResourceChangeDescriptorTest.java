package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiResourceChangeDescriptorTest {
    @Test public void onlyReviewedTypeAndOperationPairsExist() {
        int count = 0;
        for (String type : List.of("stack", "service", "container")) {
            for (String operation : List.of("start", "stop", "remove", "create", "update_metadata")) {
                assertEquals(type, AiResourceChangeDescriptor.require(type, operation).resourceType()); count++;
            }
        }
        assertEquals("restart", AiResourceChangeDescriptor.require("service", "restart").action()); count++;
        assertEquals("restart", AiResourceChangeDescriptor.require("container", "restart").action()); count++;
        assertEquals("update", AiResourceChangeDescriptor.require("service", "scale").keyOperation()); count++;
        for (String type : List.of("stack", "service")) for (String operation : List.of("archive", "restore")) {
            var archive = AiResourceChangeDescriptor.require(type, operation);
            assertEquals("PUT", archive.method()); assertEquals("update", archive.keyOperation());
            assertEquals(Map.of("archived", operation.equals("archive")), archive.body(archive.parameters(Map.of()))); count++;
            assertCode("ResourceChangeInputInvalid", () -> archive.parameters(Map.of("archived", true)));
        }
        assertEquals(22, count);
        for (String operation : List.of("restart", "shell", "execute_url", "POST"))
            assertCode("ResourceChangeUnsupported", () -> AiResourceChangeDescriptor.require("stack", operation));
        assertCode("ResourceChangeUnsupported", () -> AiResourceChangeDescriptor.require("container", "archive"));
        assertCode("ResourceChangeUnsupported", () -> AiResourceChangeDescriptor.require("credential", "remove"));
    }

    @Test public void noTransportAuthorityOrRawLaunchPayloadCanEnterAPlan() {
        var create = AiResourceChangeDescriptor.require("container", "create");
        for (String forbidden : List.of("url", "method", "headers", "accountId", "launchConfig", "environment", "privileged")) {
            Map<String, Object> input = new LinkedHashMap<>(Map.of("name", "probe", "image", "busybox:1.37.0"));
            input.put(forbidden, "do-not-store");
            assertCode("ResourceChangeInputInvalid", () -> create.parameters(input));
        }
        assertCode("ResourceChangeInputInvalid", () -> create.parameters(Map.of("name", "probe", "image", "user:secret@registry/image")));
        assertCode("ResourceChangeInputInvalid", () -> AiResourceChangeDescriptor.require("service", "create").parameters(Map.of("name", "probe",
                "image", "busybox")));
    }

    @Test public void metadataPreservesNullEmptyUnicodeAndChecksTheIntegerScaleBoundary() {
        var edit = AiResourceChangeDescriptor.require("service", "update_metadata");
        Map<String, Object> nullable = new LinkedHashMap<>(); nullable.put("description", null);
        assertTrue(edit.parameters(nullable).containsKey("description")); assertNull(edit.parameters(nullable).get("description"));
        assertEquals("", edit.parameters(Map.of("description", "")).get("description"));
        String unicode = "說明🧪".repeat(200);
        assertEquals(unicode, edit.parameters(Map.of("description", unicode)).get("description"));
        assertCode("ResourceChangeInputInvalid", () -> edit.parameters(Map.of("description", "a".repeat(1025))));
        assertCode("ResourceChangeInputInvalid", () -> edit.parameters(Map.of()));
        var scale = AiResourceChangeDescriptor.require("service", "scale");
        for (int number : List.of(0, 100)) assertEquals(number, scale.parameters(Map.of("scale", number)).get("scale"));
        for (Object invalid : List.of(-1, 101, 1.5, "2")) assertCode("ResourceChangeInputInvalid", () -> scale.parameters(Map.of("scale", invalid)));
    }

    @Test public void createProgramsRemainBoundedAndUseTheRealDomainFields() {
        var service = AiResourceChangeDescriptor.require("service", "create");
        List<String> command = List.of("sh", "-c", "while :; do sleep 1; done");
        var parameters = service.parameters(Map.of("name", "probe", "image", "busybox:1.37.0", "stackId", "1st7",
                "command", command, "entryPoint", List.of("/bin/sh")));
        var body = service.body(parameters); Map<?, ?> launch = (Map<?, ?>) body.get("launchConfig");
        assertEquals(command, launch.get("command")); assertEquals(List.of("/bin/sh"), launch.get("entryPoint"));
        assertFalse(body.containsKey("command")); assertEquals(false, body.get("startOnCreate"));
        var container = AiResourceChangeDescriptor.require("container", "create");
        assertEquals(command, container.body(container.parameters(Map.of("name", "probe", "image", "busybox", "command", command))).get("command"));
        List<String> maximum = List.of("a".repeat(4096), "a".repeat(4096), "a".repeat(4096), "a".repeat(4096));
        container.parameters(Map.of("name", "probe", "image", "busybox", "command", maximum));
        for (List<String> invalid : List.of(java.util.Collections.nCopies(65, ""), List.of("a".repeat(4097)),
                java.util.Collections.nCopies(5, "a".repeat(4096)), List.of("x\0y")))
            assertCode("ResourceChangeInputInvalid", () -> container.parameters(Map.of("name", "probe", "image", "busybox", "command", invalid)));
    }

    @Test public void realHttpSerializationKeepsNullKeysAndTheCompleteEscapedProgram() throws Exception {
        var domain = new AiResourceChangeDomain(); domain.json = new JacksonJsonMapper();
        String argument = "\n".repeat(4096); List<String> command = List.of(argument, argument, argument, argument);
        var descriptor = AiResourceChangeDescriptor.require("container", "create");
        var parameters = descriptor.parameters(Map.of("name", "probe", "image", "busybox", "command", command));
        var changes = domain.changes(descriptor, null, parameters);
        Map<String, Object> fields = new LinkedHashMap<>(); fields.put("changes", changes); fields.put("resourceId", null);
        JacksonMapper http = new JacksonMapper(); http.init(); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        http.writeValue(bytes, new ResourceImpl("aichange-11111111-1111-1111-1111-111111111111", "aiResourceChangePlan", fields));
        Map<?, ?> read = http.readValue(bytes.toByteArray(), Map.class);
        assertTrue(read.containsKey("resourceId")); assertNull(read.get("resourceId"));
        List<?> wireChanges = (List<?>) read.get("changes");
        Map<?, ?> wireCommand = (Map<?, ?>) wireChanges.stream().filter(value -> "command".equals(((Map<?, ?>) value).get("field"))).findFirst().orElseThrow();
        assertTrue(wireCommand.containsKey("before")); assertNull(wireCommand.get("before"));
        assertEquals(command, http.readValue(((String) wireCommand.get("after")).getBytes(java.nio.charset.StandardCharsets.UTF_8), ArrayList.class));
    }

    @Test public void serviceCreatePortsAndHealthCheckUseOnlyTheReviewedNativeLaunchFields() throws Exception {
        var descriptor = AiResourceChangeDescriptor.require("service", "create");
        var input = new LinkedHashMap<String, Object>(Map.of("name", "probe", "image", "busybox", "stackId", "1st3",
                "ports", List.of("18080:80/tcp", "15353:53/udp"), "healthCheck", Map.of("port", 80, "requestLine", "GET / HTTP/1.0",
                        "interval", 2000, "healthyThreshold", 2)));
        var parameters = descriptor.parameters(input); var body = descriptor.body(parameters);
        Map<?, ?> launch = (Map<?, ?>) body.get("launchConfig");
        assertEquals(input.get("ports"), launch.get("ports"));
        assertEquals(Map.of("port", 80, "requestLine", "GET / HTTP/1.0", "interval", 2000, "healthyThreshold", 2,
                "strategy", "recreate"), launch.get("healthCheck"));
        assertFalse(body.containsKey("ports")); assertFalse(body.containsKey("healthCheck"));
        assertEquals(false, body.get("startOnCreate")); assertEquals("bridge", launch.get("networkMode"));
        var domain = new AiResourceChangeDomain(); domain.json = new JacksonJsonMapper();
        var changes = domain.changes(descriptor, null, parameters);
        for (String field : List.of("ports", "healthCheck")) {
            Object summary = changes.stream().filter(change -> field.equals(change.get("field"))).findFirst().orElseThrow().get("after");
            Object decoded = new JacksonJsonMapper().readValue((String) summary, Object.class);
            assertEquals(parameters.get(field), decoded);
        }
        for (String type : List.of("container", "stack", "externalService", "dnsService"))
            for (String field : List.of("ports", "healthCheck")) {
                var unrelated = new LinkedHashMap<String, Object>(Map.of("name", "probe", "image", "busybox", "stackId", "1st3"));
                unrelated.put(field, input.get(field));
                assertCode("ResourceChangeInputInvalid", () -> AiResourceChangeDescriptor.require(type, "create").parameters(unrelated));
            }
        for (String field : List.of("environment", "launchConfig", "startOnCreate", "ports", "healthCheck"))
            assertCode("ResourceChangeInputInvalid", () -> AiResourceChangeDescriptor.require("service", "update_metadata")
                    .parameters(Map.of(field, "forbidden")));
        input.put("environment", Map.of("TOKEN", "must-never-enter-plan"));
        assertCode("ResourceChangeInputInvalid", () -> descriptor.parameters(input));
    }

    @Test public void servicePortAndHealthInputsRejectUnknownTypesAndExcessiveValuesAndKeepNativePortCodes() {
        for (Object invalid : List.of("80:80", List.of(80), List.of(""), java.util.Collections.nCopies(65, "18080:80"),
                List.of("1".repeat(129)))) assertCode("ResourceChangeInputInvalid", () -> AiServiceCreateChange.ports(invalid));
        assertCode("PortInvalidPrivatePort", () -> AiServiceCreateChange.ports(List.of("18080:65536")));
        assertCode("PortInvalidPublicPort", () -> AiServiceCreateChange.ports(List.of("65536:80")));
        assertCode("PortInvalidProtocol", () -> AiServiceCreateChange.ports(List.of("18080:80/sctp")));
        assertCode("PortWrongFormat", () -> AiServiceCreateChange.ports(List.of("9999999999999999999999:80")));
        for (Object invalid : List.of("80", Map.of(), Map.of("port", "80"), Map.of("port", 1.5), Map.of("port", 0),
                Map.of("port", 65536), Map.of("port", 80, "unknown", true), Map.of("port", 80, "strategy", "recreateOnQuorum"),
                Map.of("port", 80, "requestLine", "GET /\r\nX: injected"), Map.of("port", 80, "requestLine", "x".repeat(1025))))
            assertCode("ResourceChangeInputInvalid", () -> AiServiceCreateChange.healthCheck(invalid));
        for (String field : List.of("interval", "responseTimeout", "healthyThreshold", "unhealthyThreshold")) {
            assertCode("ResourceChangeInputInvalid", () -> AiServiceCreateChange.healthCheck(Map.of("port", 80, field, 0)));
            assertEquals(1, AiServiceCreateChange.healthCheck(Map.of("port", 80, field, 1)).get(field));
        }
        for (String field : List.of("interval", "responseTimeout", "healthyThreshold", "unhealthyThreshold", "initializingTimeout", "reinitializingTimeout")) {
            assertCode("ResourceChangeInputInvalid", () -> AiServiceCreateChange.healthCheck(Map.of("port", 80, field, -1)));
            assertCode("ResourceChangeInputInvalid", () -> AiServiceCreateChange.healthCheck(Map.of("port", 80, field, 2147483648L)));
            assertEquals(Integer.MAX_VALUE, AiServiceCreateChange.healthCheck(Map.of("port", 65535, field, 2147483647L)).get(field));
        }
        for (String field : List.of("initializingTimeout", "reinitializingTimeout"))
            assertEquals(0, AiServiceCreateChange.healthCheck(Map.of("port", 1, field, 0)).get(field));
        var nullable = new LinkedHashMap<String, Object>(); nullable.put("port", 80); nullable.put("strategy", null);
        assertCode("ResourceChangeInputInvalid", () -> AiServiceCreateChange.healthCheck(nullable));
    }

    private static void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(ClientVisibleException.class, action::run).getCode());
    }
}
