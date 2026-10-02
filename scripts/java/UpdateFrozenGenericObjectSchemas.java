import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaPostProcessor;
import io.github.ibuildthecloud.gdapi.factory.impl.SubSchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InvalidClassException;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline source migration: preserve v1 snapshots, apply only shipped low-role rules. */
class UpdateFrozenGenericObjectSchemas {
    // This one-time migration accepts only the reviewed 0.183.328 snapshots.
    // Neither a caller-provided path nor an arbitrary serialized graph is input.
    private static final ObjectInputFilter SCHEMA_FILTER = ObjectInputFilter.Config.createFilter(
            "maxdepth=80;maxrefs=100000;maxbytes=2097152;maxarray=100000;"
            + "io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;"
            + "io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;"
            + "io.github.ibuildthecloud.gdapi.model.Action;"
            + "io.github.ibuildthecloud.gdapi.model.Filter;"
            + "io.github.ibuildthecloud.gdapi.model.FieldType;"
            + "java.lang.String;java.lang.Boolean;java.lang.Long;java.lang.Number;"
            + "java.lang.Enum;java.lang.Object;java.util.ArrayList;java.util.HashMap;"
            + "java.util.LinkedHashMap;java.util.Map$Entry;!*");

    private static ObjectInputStream schemaInput(byte[] bytes) throws Exception {
        ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes));
        input.setObjectInputFilter(SCHEMA_FILTER);
        return input;
    }

    private static byte[] reviewedSnapshot(Path path, String role) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 2097152) {
            throw new IllegalArgumentException("Expected regular source snapshot: " + role);
        }
        byte[] bytes = Files.readAllBytes(path);
        String expected = "readonly".equals(role)
                ? "9be2084fabdb9f70e664009d98ca4cbfdc456645c3ad4faf683c5a8af5f83e88"
                : "2394998f427a3868d0053c445c7f2b1c690fae4e9666174d5ee4ee938f58e96d";
        if (!java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(expected)) {
            throw new IllegalArgumentException("Unreviewed or already migrated source snapshot: " + role);
        }
        return bytes;
    }

    private static class UnexpectedGraph implements Serializable {
        private static final long serialVersionUID = 1L;
        static boolean executed;

        private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
            executed = true;
            input.defaultReadObject();
        }
    }

    private static void checkFilter() throws Exception {
        for (String role : Arrays.asList("readonly", "restricted")) {
            byte[] bytes = Files.readAllBytes(Paths.get("resources/content/schema/v1/" + role + ".ser"));
            try (ObjectInputStream input = schemaInput(bytes)) {
                List<?> schemas = (List<?>) input.readObject();
                if (schemas.isEmpty()) {
                    throw new AssertionError("Empty schema snapshot");
                }
                for (Object schema : schemas) {
                    Schema.class.cast(schema);
                }
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(new UnexpectedGraph());
        }
        try (ObjectInputStream input = schemaInput(bytes.toByteArray())) {
            input.readObject();
            throw new AssertionError("Unexpected serialized class accepted");
        } catch (InvalidClassException expected) {
            if (UnexpectedGraph.executed) {
                throw new AssertionError("Rejected graph executed its callback");
            }
        }
        System.out.println("FROZEN_SCHEMA_FILTER_OK known_roles=2 unexpected_graph_rejected=1 callback_executed=0");
    }

    private static class Overlay extends AuthOverlayPostProcessor {
        void document(Map<?, ?> doc) {
            Map<?, ?> declared = (Map<?, ?>) doc.get("authorize");
            Map<String, String> changed = new LinkedHashMap<String, String>();
            for (String name : Arrays.asList("genericObject.key", "genericObject.resourceData")) {
                if (!"".equals(declared.get(name))) {
                    throw new IllegalStateException("Expected explicit field denial: " + name);
                }
                changed.put(name, "");
            }
            load(changed);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--check-filter".equals(args[0])) {
            checkFilter();
            return;
        }
        if (args.length != 0) {
            throw new IllegalArgumentException("Run from repository root; no path arguments accepted");
        }
        Path root = Paths.get(".").toRealPath();
        ObjectMapper mapper = new ObjectMapper();
        Map<Path, byte[]> outputs = new LinkedHashMap<Path, byte[]>();
        for (String role : Arrays.asList("readonly", "restricted")) {
            String overlay = "readonly".equals(role) ? "read-user" : "restricted-user";
            Path path = root.resolve("resources/content/schema/v1/" + role + ".ser");
            List<Schema> original = new ArrayList<Schema>();
            Map<String, Schema> index = new LinkedHashMap<String, Schema>();
            try (ObjectInputStream input = schemaInput(reviewedSnapshot(path, role))) {
                for (Object item : (List<?>) input.readObject()) {
                    Schema schema = Schema.class.cast(item);
                    original.add(schema);
                    index.put(schema.getId(), schema);
                }
            }
            SchemaFactory parent = (SchemaFactory) Proxy.newProxyInstance(
                    SchemaFactory.class.getClassLoader(), new Class<?>[] {SchemaFactory.class},
                    (proxy, method, values) -> {
                        if ("listSchemas".equals(method.getName())) {
                            return original;
                        }
                        return "getSchema".equals(method.getName()) ? index.get(values[0]) : null;
                    });
            Overlay auth = new Overlay();
            auth.document(mapper.readValue(root.resolve("resources/content/schema/" + overlay + "/"
                    + overlay + ".json").toFile(), Map.class));
            SubSchemaFactory factory = new SubSchemaFactory();
            factory.setId(role);
            factory.setSchemaFactory(parent);
            factory.setPostProcessors(Arrays.<SchemaPostProcessor>asList(auth));
            factory.init();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(factory.listSchemas());
            }
            // Compare the persisted contract, not non-serializable ResourceImpl
            // constructor state (type/baseType) on the in-memory factory copy.
            List<Schema> result = new ArrayList<Schema>();
            try (ObjectInputStream input = schemaInput(bytes.toByteArray())) {
                for (Object item : (List<?>) input.readObject()) {
                    result.add(Schema.class.cast(item));
                }
            }
            if (result.size() != original.size()) {
                throw new IllegalStateException("Unexpected schema removal: " + role);
            }
            int changed = 0;
            for (Schema schema : result) {
                Schema before = index.get(schema.getId());
                JsonNode expected = mapper.valueToTree(before);
                Schema ancestor = before;
                while (ancestor != null && !"genericObject".equals(ancestor.getId())) {
                    ancestor = index.get(ancestor.getParent());
                }
                if (ancestor != null) {
                    ObjectNode fields = (ObjectNode) expected.get("resourceFields");
                    if (fields.remove("key") != null | fields.remove("resourceData") != null) {
                        changed++;
                    }
                }
                JsonNode actual = mapper.valueToTree(schema);
                if (!expected.equals(actual)) {
                    expected.fieldNames().forEachRemaining(name -> {
                        if (!expected.get(name).equals(actual.get(name))) {
                            System.err.println("Changed schema property: " + name);
                        }
                    });
                    throw new IllegalStateException("Unrelated frozen schema drift: " + role + "/" + schema.getId());
                }
            }
            if (changed == 0) {
                throw new IllegalStateException("Already migrated or missing generic storage: " + role);
            }
            outputs.put(path, bytes.toByteArray());
            System.out.println(role + ": verified field-only changes in " + changed + " schemas");
        }
        // Validate both roles before writing either source artifact.
        for (Map.Entry<Path, byte[]> entry : outputs.entrySet()) {
            try (FileOutputStream output = new FileOutputStream(entry.getKey().toFile())) {
                output.write(entry.getValue());
            }
        }
    }
}
