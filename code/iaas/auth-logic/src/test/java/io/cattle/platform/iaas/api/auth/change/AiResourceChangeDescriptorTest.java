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
        assertEquals(18, count);
        for (String operation : List.of("restart", "archive", "restore", "shell", "execute_url", "POST"))
            assertCode("ResourceChangeUnsupported", () -> AiResourceChangeDescriptor.require("stack", operation));
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

    private static void assertCode(String code, Runnable action) {
        assertEquals(code, assertThrows(ClientVisibleException.class, action::run).getCode());
    }
}
