package io.cattle.platform.framework.secret;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class SecretsServiceImplTest {

    @Test
    public void encryptedKeyMapUsesCheckedStringKeys() {
        SecretsServiceImpl service = new SecretsServiceImpl();
        Map<Object, Object> input = new HashMap<Object, Object>();
        input.put("encryptedText", "cipher-key");

        Map<String, Object> result = service.stringObjectMap(input);

        assertEquals("cipher-key", result.get("encryptedText"));
    }

    @Test
    public void encryptedKeyMapRejectsNonStringKeysAtBoundary() {
        SecretsServiceImpl service = new SecretsServiceImpl();
        Map<Object, Object> input = new HashMap<Object, Object>();
        input.put(Integer.valueOf(1), "cipher-key");

        try {
            service.stringObjectMap(input);
            fail("Expected non-string keys to be rejected");
        } catch (ClassCastException expected) {
            // Expected boundary failure.
        }
    }
}
