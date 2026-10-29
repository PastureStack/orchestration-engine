package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Restart-invalidated options snapshots. The public reference contains no private digest or authorization grant. */
public class AiCatalogLaunchOptionsStore {
    record Binding(long actor, long project, Long key, Long revision, String kind) { }
    record Snapshot(Binding binding, String template, String version, String digest, List<Map<String, Object>> questions, long expiresAt) { }
    private final Map<String, Snapshot> snapshots = new LinkedHashMap<>();
    Clock clock = Clock.systemUTC();

    static Binding binding(ApiRequest request, Policy actor) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        return new Binding(actor.getAuthenticatedAsAccountId(), actor.getAccountId(), key == null ? null : key.credentialId(),
                key == null ? null : key.revision(), key == null ? null : key.kind());
    }

    synchronized String put(Binding binding, String template, String version, String digest, List<Map<String, Object>> questions) {
        snapshots.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= clock.millis());
        long owned = snapshots.values().stream().filter(snapshot -> snapshot.binding().actor() == binding.actor()).count();
        if (owned >= 32 || snapshots.size() >= 1024) throw error(503, "CatalogLaunchUnavailable");
        String ref = "acopts-" + UUID.randomUUID();
        snapshots.put(ref, new Snapshot(binding, template, version, digest,
                questions.stream().map(question -> java.util.Collections.unmodifiableMap(new LinkedHashMap<>(question))).toList(), clock.millis() + 900000));
        return ref;
    }

    synchronized Snapshot get(String ref, Binding binding, String template, String version) {
        Snapshot snapshot = snapshots.get(ref);
        if (snapshot == null || snapshot.expiresAt() <= clock.millis() || !snapshot.binding().equals(binding)
                || !Objects.equals(snapshot.template(), template) || !Objects.equals(snapshot.version(), version))
            throw error(409, "CatalogLaunchSourceChanged");
        return snapshot;
    }
}
