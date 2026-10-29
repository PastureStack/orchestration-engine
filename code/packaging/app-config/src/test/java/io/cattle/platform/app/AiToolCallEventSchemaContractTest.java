package io.cattle.platform.app;

import static org.junit.Assert.*;

import io.cattle.platform.api.auth.ClientToolAuditEvent;
import io.cattle.platform.json.JacksonJsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

/** The real request validator must accept exactly the server's bounded audit vocabulary. */
public class AiToolCallEventSchemaContractTest {
    @Test public void schemaEnumsExactlyMatchTheSharedServerConstants() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("resources/content/schema"))) root = root.getParent();
        assertNotNull(root);
        var json = new JacksonJsonMapper();
        Map<?, ?> schema = json.readValue(Files.readString(root.resolve("resources/content/schema/base/aiToolCallEvent.json")), Map.class);
        Map<?, ?> fields = (Map<?, ?>) schema.get("resourceFields");
        for (var entry : Map.of("command", ClientToolAuditEvent.COMMANDS,
                "status", ClientToolAuditEvent.STATUSES, "reason", ClientToolAuditEvent.REASONS).entrySet()) {
            Map<?, ?> field = (Map<?, ?>) fields.get(entry.getKey());
            assertEquals(entry.getKey(), "enum", field.get("type"));
            List<?> options = (List<?>) field.get("options");
            Set<?> actual = Set.copyOf(options);
            assertEquals(entry.getKey() + " must not contain duplicates", actual.size(), options.size());
            assertEquals(entry.getKey(), entry.getValue(), actual);
        }
    }
}
