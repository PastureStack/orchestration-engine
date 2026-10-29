package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Source-only C-03 projection. Does not claim role/state availability, handler
 * side effects, native HTTP or asynchronous completion. Unsupported stays denied.
 * The compact reviewed rows are bound to the schema/handler/process source bytes;
 * changing that input union requires re-extraction/review, not silent regeneration.
 * Source-tuple extraction is established by the reviewed offline extractor and
 * its artifact. The mutations below test fixture set equality and unknown-action
 * admission only; they do not prove the extractor cannot omit a source tuple.
 */
@RunWith(Parameterized.class)
public class ApiKeyOperationInventoryProjectionTest {
    private static final Path ROOT = repositoryRoot();
    private static final JsonNode CONTRACT = contract();
    private static final Pattern IMPLEMENTATION = Pattern.compile("\\bimplements[^\\{]*\\b(?:ActionHandler|LinkHandler)\\b");
    private final List<String> row;

    public ApiKeyOperationInventoryProjectionTest(String label, List<String> row) { this.row = row; }

    @Parameterized.Parameters(name = "{0}")
    public static Object[][] cases() {
        List<Object[]> cases = new ArrayList<>();
        for (JsonNode item : CONTRACT.path("tuples")) {
            List<String> row = new ArrayList<>();
            item.forEach(v -> row.add(v.asText()));
            cases.add(new Object[] {String.join("/", row), List.copyOf(row)});
        }
        cases.add(new Object[] {"source coverage and unknown-action mutation", List.of()});
        return cases.toArray(Object[][]::new);
    }

    @After public void cleanup() { ApiContext.remove(); }

    @Test public void projectedTupleKeepsItsReviewedSemanticAndAdmissionDisposition() throws Exception {
        if (row.isEmpty()) { sourceCoverageAndUnknownMutation(); return; }
        assertEquals("tuple columns", 6, row.size());
        String type = row.get(0), method = row.get(2), action = nullable(row.get(3)), link = nullable(row.get(4));
        String expected = row.get(5);
        var actual = ApiKeyOperations.of(method, action, link, type);
        assertEquals(row.toString(), expected, actual.id());
        boolean registered = !expected.startsWith("action:") && !expected.startsWith("method:")
                && !expected.startsWith("ai-plan:") && !expected.startsWith("ai-change:") && !expected.startsWith("ai-catalog-launch:");
        assertEquals(row.toString(), registered, actual.registered());
        if (!registered) {
            assertTrue("unreviewed effect " + action,
                    CONTRACT.path("semantics").path("unsupported").has(action.toLowerCase(java.util.Locale.ROOT)));
            unsupportedCannotGainAuthority(type, method, action, link, row.get(1));
        }
    }

    private static void unsupportedCannotGainAuthority(String type, String method, String action, String link, String scope) {
        ApiContext.newContext().setIdFormatter(new IdentityFormatter());
        Policy owner = mock(Policy.class);
        Schema schema = mock(Schema.class);
        when(schema.getResourceActions()).thenReturn(Map.of(action, mock(Action.class)));
        when(schema.getCollectionActions()).thenReturn(Map.of(action, mock(Action.class)));
        SchemaFactory factory = mock(SchemaFactory.class);
        when(factory.getSchema(type)).thenReturn(schema);
        ApiRequest request = new ApiRequest(null, factory);
        request.setType(type); request.setMethod(method); request.setAction(action); request.setLink(link);
        request.setId("resource".equals(scope) ? "contract-only-id" : null);
        request.setRequestObject(Map.of());
        ApiContext.getContext().setPolicy(owner); ApiContext.getContext().setApiRequest(request);
        ApiKeyAuthorizationService guard = new ApiKeyAuthorizationService();
        guard.targets = mock(ApiKeyTargetResolver.class);
        ApiKeyAuditSink audit = mock(ApiKeyAuditSink.class); guard.auditSinks = List.of(audit);
        ApiKeyPolicy custom = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null,
                List.of(new ApiKeyPolicy.Rule("all-known-operations", ApiKeyPolicy.Effect.ALLOW,
                        ApiKeyPolicy.Scope.global(), Set.copyOf(ApiKeyOperations.OPERATIONS))));
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 1, "apiKey", 1, custom));
        String canonical = ApiKeyQueryScopes.canonical(type);
        boolean humanApproval = Set.of("aiServiceUpgradePlan", "aiResourceChangePlan", "aiCatalogStackLaunchPlan").contains(type)
                && Set.of("confirm", "cancel", "completeInputs").contains(action);
        String code = humanApproval ? "AiKeyConfirmationForbidden" : ("apiKey".equals(canonical) || "token".equals(canonical))
                ? "ApiKeyRestrictedDelegationUnsupported" : "UnknownOperation";
        assertEquals(code, assertThrows(ClientVisibleException.class, () -> guard.authorize(request, owner)).getCode());
        assertEquals("DENY", request.getAttribute("apiKey.audit.decision"));
        UUID.fromString((String) request.getAttribute("apiKey.audit.requestId"));
        verifyNoInteractions(guard.targets);
        for (ApiKeyPolicy policy : new ApiKeyPolicy[] {null,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of())}) {
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 1, "apiKey", 1, policy));
            if (humanApproval) {
                assertEquals(code, assertThrows(ClientVisibleException.class, () -> guard.authorize(request, owner)).getCode());
                assertEquals("DENY", request.getAttribute("apiKey.audit.decision"));
            } else {
                guard.authorize(request, owner);
                assertEquals("ALLOW", request.getAttribute("apiKey.audit.decision"));
            }
            UUID.fromString((String) request.getAttribute("apiKey.audit.requestId"));
        }
        verifyNoInteractions(guard.targets);
        verify(audit, times(3)).recordDecision(request, owner);
    }

    private static void sourceCoverageAndUnknownMutation() throws Exception {
        assertEquals(1, CONTRACT.path("schemaVersion").asInt());
        JsonNode inputs = CONTRACT.path("sourceInputs");
        assertEquals("c03-source-inputs-v1", inputs.path("algorithm").asText());
        Set<String> anchors = strings(inputs.path("anchors"));
        Set<String> markers = strings(inputs.path("markers"));
        Map<String, String> sources = new TreeMap<>();
        for (String folder : List.of("code", "resources")) {
            try (var paths = Files.walk(ROOT.resolve(folder))) {
                for (Path file : paths.filter(Files::isRegularFile).toList()) {
                    String relative = ROOT.relativize(file).toString().replace('\\', '/');
                    if (relative.contains("/target/")) continue;
                    boolean frozen = relative.startsWith("resources/content/schema/v1/") && relative.endsWith(".ser");
                    boolean json = relative.contains("/schema/") && relative.endsWith(".json");
                    boolean java = relative.contains("/src/main/java/") && relative.endsWith(".java");
                    boolean xml = relative.contains("/src/main/resources/META-INF/cattle/") && relative.endsWith(".xml");
                    if (!frozen && !json && !java && !xml) continue;
                    byte[] raw = Files.readAllBytes(file);
                    String text = frozen ? "" : new String(raw, StandardCharsets.UTF_8);
                    if (frozen || json || (xml && text.contains("<process:")) || (java &&
                            (anchors.contains(file.getFileName().toString()) || relative.endsWith("Constants.java") ||
                             IMPLEMENTATION.matcher(text).find() || markers.stream().anyMatch(text::contains)))) {
                        sources.put(relative, sha(frozen ? raw : text.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
                    }
                }
            }
        }
        assertEquals("source input set changed: re-extract and review", inputs.path("count").asInt(), sources.size());
        StringBuilder fingerprint = new StringBuilder();
        sources.forEach((path, hash) -> fingerprint.append(path).append(' ').append(hash).append('\n'));
        assertEquals("source bytes changed: re-extract and review", inputs.path("sha256").asText(),
                sha(fingerprint.toString().getBytes(StandardCharsets.UTF_8)));
        Set<String> expected = new TreeSet<>();
        StringBuilder tuples = new StringBuilder();
        for (JsonNode item : CONTRACT.path("tuples")) {
            List<String> value = new ArrayList<>(); item.forEach(v -> value.add(v.asText()));
            assertEquals(6, value.size());
            assertTrue("duplicate tuple", expected.add(String.join("|", value.subList(0, 5))));
            tuples.append(String.join("|", value)).append('\n');
        }
        assertEquals(CONTRACT.path("tupleCount").asInt(), expected.size());
        assertEquals(CONTRACT.path("tupleSha256").asText(), sha(tuples.toString().getBytes(StandardCharsets.UTF_8)));
        Set<String> extra = new HashSet<>(expected);
        assertTrue(extra.add("container|resource|POST|futureUnreviewedAction|"));
        assertThrows(AssertionError.class, () -> assertCoverage(expected, extra));
        Set<String> missing = new HashSet<>(expected); missing.remove(missing.iterator().next());
        assertThrows(AssertionError.class, () -> assertCoverage(expected, missing));
        assertFalse(ApiKeyOperations.of("POST", "futureUnreviewedAction", null, "container").registered());
        unsupportedCannotGainAuthority("container", "POST", "futureUnreviewedAction", null, "resource");
    }

    private static void assertCoverage(Set<String> expected, Set<String> observed) {
        assertEquals("unreviewed new or missing source tuple", expected, observed);
    }
    private static Set<String> strings(JsonNode array) {
        Set<String> values = new HashSet<>(); array.forEach(v -> values.add(v.asText())); return values;
    }
    private static String nullable(String value) { return value.isEmpty() ? null : value; }
    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    private static Path repositoryRoot() {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/v1/owner.ser"))) root = root.getParent();
        if (root == null) throw new AssertionError("repository root missing");
        return root;
    }
    private static JsonNode contract() {
        try { return new ObjectMapper().readTree(ROOT.resolve("scripts/api-key-operation-contract.json").toFile()); }
        catch (Exception error) { throw new AssertionError("operation contract missing or invalid", error); }
    }
}
