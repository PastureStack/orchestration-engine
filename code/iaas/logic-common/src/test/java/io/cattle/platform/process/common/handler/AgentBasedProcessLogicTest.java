package io.cattle.platform.process.common.handler;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.archaius.util.ConfigProperty;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class AgentBasedProcessLogicTest {

    @Test
    public void expressionReadsDynamicConfigThroughWrapper() {
        final String key = "event.data.instance";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "name,uuid,state");

            TestAgentBasedProcessLogic logic = new TestAgentBasedProcessLogic();
            assertEquals("name,uuid,state", logic.expressionFor("instance"));
        } finally {
            clearProperty(key);
        }
    }

    @Test
    public void explicitExpressionStillOverridesDynamicConfig() {
        final String key = "event.data.instance";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "configured");

            TestAgentBasedProcessLogic logic = new TestAgentBasedProcessLogic();
            logic.setExpression("explicit");

            assertEquals("explicit", logic.expressionFor("instance"));
        } finally {
            clearProperty(key);
        }
    }

    @Test
    public void expressionPropertyUsesConfiguredPrefix() {
        final String key = "custom.prefix.host";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "hostname");

            TestAgentBasedProcessLogic logic = new TestAgentBasedProcessLogic();
            logic.setConfigPrefix("custom.prefix.");

            assertEquals("hostname", logic.expressionPropertyFor("host").get());
        } finally {
            clearProperty(key);
        }
    }

    private void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static class TestAgentBasedProcessLogic extends AgentBasedProcessLogic {

        String expressionFor(String type) {
            return getExpression(type);
        }

        ConfigProperty<String> expressionPropertyFor(String type) {
            return getExpressionProperty(type);
        }
    }
}
