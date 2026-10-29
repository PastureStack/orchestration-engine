package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiCatalogLaunchOptionsStoreTest {
    @Test public void snapshotIsBoundToOwnerKeyRevisionSelectionExpiryAndServerLifetime() {
        var store = new AiCatalogLaunchOptionsStore(); Instant now = Instant.parse("2026-10-30T00:00:00Z");
        store.clock = Clock.fixed(now, ZoneOffset.UTC);
        var binding = new AiCatalogLaunchOptionsStore.Binding(42, 5, 7L, 3L, "apiKeyRestricted");
        String ref = store.put(binding, "template", "version", "private-digest", List.of());
        assertEquals("private-digest", store.get(ref, binding, "template", "version").digest());
        assertFalse(ref.contains("digest"));
        for (var other : List.of(new AiCatalogLaunchOptionsStore.Binding(99, 5, 7L, 3L, "apiKeyRestricted"),
                new AiCatalogLaunchOptionsStore.Binding(42, 88, 7L, 3L, "apiKeyRestricted"),
                new AiCatalogLaunchOptionsStore.Binding(42, 5, 7L, 4L, "apiKeyRestricted"),
                new AiCatalogLaunchOptionsStore.Binding(42, 5, null, null, null)))
            assertThrows(ClientVisibleException.class, () -> store.get(ref, other, "template", "version"));
        assertThrows(ClientVisibleException.class, () -> store.get(ref, binding, "different", "version"));
        assertThrows(ClientVisibleException.class, () -> new AiCatalogLaunchOptionsStore().get(ref, binding, "template", "version"));
        store.clock = Clock.fixed(now.plusSeconds(901), ZoneOffset.UTC);
        assertEquals("CatalogLaunchSourceChanged", assertThrows(ClientVisibleException.class,
                () -> store.get(ref, binding, "template", "version")).getCode());
    }

    @Test public void safeQuestionProjectionNeverCopiesDefaultsAnswersOrPrivateDefinitions() {
        Map<String, Object> raw = new java.util.LinkedHashMap<>(Map.of("name", "password", "type", "human", "required", false,
                "requiresHuman", true, "label", "Password", "description", "Human input", "sourceType", "password"));
        raw.put("default", "private-default"); raw.put("answer", "private-answer"); raw.put("files", Map.of("secret.yml", "private-yaml"));
        var safe = AiCatalogLaunchInput.questions(List.of(raw));
        assertFalse(safe.toString().contains("private-")); assertFalse(safe.getFirst().containsKey("default"));
        assertEquals(true, safe.getFirst().get("requiresHuman")); assertEquals(false, safe.getFirst().get("required"));
        raw.put("name", "__proto__"); assertThrows(ClientVisibleException.class, () -> AiCatalogLaunchInput.questions(List.of(raw)));
    }
}
