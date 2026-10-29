package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.framework.secret.SecretsService;
import io.cattle.platform.json.JsonMapper;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Existing SecretsService encryption, with plan/actor/project/Key binding inside the authenticated plaintext. */
public class AiCatalogLaunchPrivate {
    private static final List<String> BINDING = List.of("id", "actorId", "accountId", "credentialId", "credentialRevision", "credentialKind");
    private static final int MAX_CLEAR_BYTES = 512 * 1024;
    @Inject SecretsService secrets;
    @Inject JsonMapper json;

    public String seal(Map<String, Object> plan, Map<String, Object> values) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("version", 1); envelope.put("binding", binding(plan)); envelope.put("values", values);
            String clear = json.writeValueAsString(envelope);
            if (clear.getBytes(StandardCharsets.UTF_8).length > MAX_CLEAR_BYTES) throw error(422, "CatalogLaunchInputInvalid");
            String encrypted = secrets.encrypt(((Number) plan.get("accountId")).longValue(), clear);
            if (encrypted == null || encrypted.isBlank() || encrypted.equals(clear) || encrypted.length() > 1048576)
                throw error(503, "CatalogLaunchUnavailable");
            return encrypted;
        } catch (io.github.ibuildthecloud.gdapi.exception.ClientVisibleException bounded) { throw bounded; }
        catch (Exception unavailable) { throw error(503, "CatalogLaunchUnavailable"); }
    }

    public Map<String, Object> open(Map<String, Object> plan) {
        try {
            String encrypted = text(plan.get("privateEnvelope"), 1, 1048576);
            String clear = secrets.decrypt(((Number) plan.get("accountId")).longValue(), encrypted);
            if (clear == null || clear.getBytes(StandardCharsets.UTF_8).length > MAX_CLEAR_BYTES) throw error(503, "CatalogLaunchUnavailable");
            Map<String, Object> envelope = json.readValue(clear);
            if (!(envelope.get("version") instanceof Number version) || version.intValue() != 1) throw error(503, "CatalogLaunchUnavailable");
            Map<String, Object> actual = map(envelope.get("binding")), expected = binding(plan);
            if (!actual.keySet().equals(expected.keySet())) throw error(503, "CatalogLaunchUnavailable");
            for (String field : BINDING) {
                Object left = actual.get(field), right = expected.get(field);
                boolean matches = left instanceof Number a && right instanceof Number b ? a.longValue() == b.longValue() : Objects.equals(left, right);
                if (!matches) throw error(503, "CatalogLaunchUnavailable");
            }
            return map(envelope.get("values"));
        } catch (Exception unavailable) { throw error(503, "CatalogLaunchUnavailable"); }
    }

    private Map<String, Object> binding(Map<String, Object> plan) {
        Map<String, Object> binding = new LinkedHashMap<>();
        for (String field : BINDING) binding.put(field, plan.get(field));
        id(binding.get("id"), "aicataloglaunch-[0-9a-f-]{36}");
        if (!(binding.get("actorId") instanceof Number actor) || actor.longValue() < 1
                || !(binding.get("accountId") instanceof Number project) || project.longValue() < 1)
            throw error(503, "CatalogLaunchUnavailable");
        return binding;
    }
}
