package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.framework.secret.SecretsService;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

public class AiCatalogLaunchPrivateTest {
    @Test public void existingSecretsServiceEnvelopeBindsAnswersToPlanActorProjectAndOriginalKey() throws Exception {
        AiCatalogLaunchPrivate vault = new AiCatalogLaunchPrivate(); vault.secrets = mock(SecretsService.class); vault.json = new JacksonJsonMapper();
        Map<String, String> backend = new LinkedHashMap<>();
        when(vault.secrets.encrypt(eq(5L), anyString())).thenAnswer(call -> {
            String cipher = "encrypted-" + UUID.randomUUID(); backend.put(cipher, call.getArgument(1)); return cipher;
        });
        when(vault.secrets.decrypt(eq(5L), anyString())).thenAnswer(call -> backend.get(call.getArgument(1)));
        Map<String, Object> plan = plan(); String password = "synthetic-private-human-answer";
        String cipher = vault.seal(plan, Map.of("humanAnswers", Map.of("password", password)));
        assertFalse(cipher.contains(password)); plan.put("privateEnvelope", cipher);
        assertEquals(Map.of("humanAnswers", Map.of("password", password)), vault.open(plan));
        for (var mutation : Map.of("id", "aicataloglaunch-22222222-2222-2222-2222-222222222222", "actorId", 99L,
                "accountId", 88L, "credentialId", 8L, "credentialRevision", 4L, "credentialKind", "apiKey").entrySet()) {
            Map<String, Object> other = new LinkedHashMap<>(plan); other.put(mutation.getKey(), mutation.getValue());
            var denied = assertThrows(ClientVisibleException.class, () -> vault.open(other));
            assertEquals("CatalogLaunchUnavailable", denied.getCode()); assertFalse(denied.getMessage().contains(password)); assertNull(denied.getCause());
        }
    }

    @Test public void noOpEncryptionAndBackendErrorsNeverPersistPlaintextOrExposeTheirCause() throws Exception {
        AiCatalogLaunchPrivate vault = new AiCatalogLaunchPrivate(); vault.secrets = mock(SecretsService.class); vault.json = new JacksonJsonMapper();
        when(vault.secrets.encrypt(anyLong(), anyString())).thenAnswer(call -> call.getArgument(1));
        assertEquals("CatalogLaunchUnavailable", assertThrows(ClientVisibleException.class,
                () -> vault.seal(plan(), Map.of("humanAnswers", Map.of("password", "private")))).getCode());
        when(vault.secrets.encrypt(anyLong(), anyString())).thenThrow(new java.io.IOException("private upstream payload"));
        var denied = assertThrows(ClientVisibleException.class, () -> vault.seal(plan(), Map.of()));
        assertEquals("CatalogLaunchUnavailable", denied.getCode()); assertNull(denied.getCause());
        assertFalse(denied.getMessage().contains("upstream"));
    }

    private static Map<String, Object> plan() {
        return new LinkedHashMap<>(Map.of("id", "aicataloglaunch-11111111-1111-1111-1111-111111111111", "actorId", 42L,
                "accountId", 5L, "credentialId", 7L, "credentialRevision", 3L, "credentialKind", "apiKeyRestricted"));
    }
}
