package io.cattle.platform.register.api;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class RegisterScriptHandlerTest {

    @Test
    public void replacesRegistrationScriptTokensAndLeavesUnknownVariables() {
        RegisterScriptHandler handler = new RegisterScriptHandler();
        Map<String, String> tokens = new HashMap<>();
        tokens.put("CATTLE_URL", "https://pasturestack.example/v1");
        tokens.put("CATTLE_AGENT_IMAGE", "ghcr.io/pasturestack/node-agent:v1.2.29");
        tokens.put("CATTLE_REGISTRATION_SECRET_KEY", "secret");

        String script = "url=${CATTLE_URL}\nimage=${CATTLE_AGENT_IMAGE}\nsecret=${CATTLE_REGISTRATION_SECRET_KEY}\nmissing=${MISSING}";

        assertEquals("url=https://pasturestack.example/v1\n"
                + "image=ghcr.io/pasturestack/node-agent:v1.2.29\n"
                + "secret=secret\n"
                + "missing=${MISSING}", handler.replaceTokens(script, tokens));
    }
}
