package io.cattle.platform.agent.util;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class AgentUtilsTest {

    @Test
    public void agentResourcesReadsDynamicListThroughWrapper() {
        final String key = "agent.resources";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "host,instance");

            assertEquals(Arrays.asList("host", "instance"), AgentUtils.AGENT_RESOURCES.get());
        } finally {
            if (ConfigurationManager.getConfigInstance().containsKey(key)) {
                ConfigurationManager.getConfigInstance().clearProperty(key);
            }
        }
    }
}
