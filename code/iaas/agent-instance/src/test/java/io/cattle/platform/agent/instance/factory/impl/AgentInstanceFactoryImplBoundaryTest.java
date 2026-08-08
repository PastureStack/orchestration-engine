package io.cattle.platform.agent.instance.factory.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class AgentInstanceFactoryImplBoundaryTest {

    @Test
    public void launchConfigMapKeepsMapValues() {
        Map<String, Object> launchConfig = new LinkedHashMap<String, Object>();
        launchConfig.put("imageUuid", "docker:repo/image:tag");

        Map<?, ?> result = AgentInstanceFactoryImpl.launchConfigMap(launchConfig);

        assertEquals("docker:repo/image:tag", result.get("imageUuid"));
    }

    @Test
    public void launchConfigMapDefaultsNullToEmptyMap() {
        assertTrue(AgentInstanceFactoryImpl.launchConfigMap(null).isEmpty());
    }

    @Test
    public void launchConfigMapRejectsNonMapValues() {
        try {
            AgentInstanceFactoryImpl.launchConfigMap("docker:repo/image:tag");
            fail("Expected non-map launch config to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.util.Map"));
        }
    }

    @Test
    public void networkIdsKeepsLongIds() {
        List<Long> ids = AgentInstanceFactoryImpl.networkIds(Arrays.asList(1L, 2L));

        assertEquals(Arrays.asList(1L, 2L), ids);
    }

    @Test
    public void networkIdsDefaultsNullToEmptyList() {
        assertEquals(Collections.emptyList(), AgentInstanceFactoryImpl.networkIds(null));
    }

    @Test
    public void networkIdsRejectsNonListValues() {
        try {
            AgentInstanceFactoryImpl.networkIds(1L);
            fail("Expected non-list networkIds to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.util.List"));
        }
    }

    @Test
    public void networkIdsRejectsNonLongElements() {
        try {
            AgentInstanceFactoryImpl.networkIds(Arrays.asList(1));
            fail("Expected non-Long network id to be rejected");
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains("java.lang.Long"));
        }
    }
}
