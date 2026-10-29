package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.json.JsonMapper;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Fixed same-container owner calls. Private material is never a public resource or audit payload. */
public class AiCatalogLaunchGateway {
    static final URI MATERIAL = URI.create("http://127.0.0.1:8088/v1-catalog/ai/launch-material");
    static final URI VALIDATE = URI.create("http://127.0.0.1:8088/v1-catalog/ai/launch-validate");
    static final int MAX_BYTES = 4 * 1024 * 1024;
    @Inject JsonMapper json;
    final AiCatalogMetadataGateway.Transport transport;

    public AiCatalogLaunchGateway() {
        var defaults = new AiCatalogMetadataGateway.HttpTransport();
        transport = new AiCatalogMetadataGateway.HttpTransport(defaults.client, 5000, MAX_BYTES);
    }
    AiCatalogLaunchGateway(AiCatalogMetadataGateway.Transport transport) { this.transport = transport; }

    public Map<String, Object> material(String project, String template, String version) {
        id(template, TEMPLATE); id(version, VERSION);
        URI uri = URI.create(MATERIAL + "?templateId=" + template + "&versionId=" + version);
        Map<String, Object> result = exchange(builder(project, uri).GET().build());
        if (!template.equals(result.get("templateId")) || !version.equals(result.get("versionId")))
            throw error(503, "CatalogLaunchUnavailable");
        if (!(result.get("digest") instanceof String digest) || !digest.matches("[0-9a-f]{64}")) throw error(503, "CatalogLaunchUnavailable");
        return result;
    }

    public Map<String, Object> validate(String project, Map<String, Object> input) {
        byte[] body;
        try { body = json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8); }
        catch (IOException failure) { throw error(422, "CatalogLaunchInputInvalid"); }
        if (body.length > 256 * 1024) throw error(422, "CatalogLaunchInputInvalid");
        return exchange(builder(project, VALIDATE).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build());
    }

    private HttpRequest.Builder builder(String project, URI uri) {
        if (project == null || !project.matches("1a[1-9][0-9]{0,18}")) throw error(403, "OwnerPermissionDenied");
        return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4)).header("Accept", "application/json")
                .header("X-API-Project-Id", project);
    }

    private Map<String, Object> exchange(HttpRequest request) {
        AiCatalogMetadataGateway.Reply reply;
        try { reply = transport.exchange(request); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw error(503, "CatalogLaunchUnavailable"); }
        catch (IOException | RuntimeException failure) { throw error(503, "CatalogLaunchUnavailable"); }
        if (reply.status() == 404 || reply.status() == 409) throw error(409, "CatalogLaunchSourceChanged");
        if (reply.status() == 422) {
            String code = "CatalogLaunchInputInvalid";
            try {
                if (reply.body() != null && reply.body().length <= 4096) {
                    Object reported = json.<Map<String, Object>>readValue(reply.body()).get("code");
                    if (java.util.Set.of("CatalogLaunchOptionUnknown", "CatalogLaunchUnsupported").contains(reported)) code = (String) reported;
                }
            } catch (IOException | RuntimeException invalid) { /* A private upstream message is never exposed. */ }
            throw error(422, code);
        }
        if (reply.status() != 200 || reply.body() == null || reply.body().length > MAX_BYTES) throw error(503, "CatalogLaunchUnavailable");
        try { return json.readValue(reply.body()); }
        catch (IOException | RuntimeException invalid) { throw error(503, "CatalogLaunchUnavailable"); }
    }
}
