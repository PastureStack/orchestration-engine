package io.cattle.platform.engine.process.impl;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.json.JacksonJsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class AbstractStatesBasedProcessStateTest {

    @Test
    public void convertDataUsesCheckedMapBoundary() {
        TestProcessState state = new TestProcessState();
        Map<Object, Object> data = new LinkedHashMap<Object, Object>();
        data.put(42L, "value");

        Map<String, Object> result = state.convertData(data);

        assertEquals("value", result.get("42"));
    }

    @Test
    public void convertDataDelegatesNonMapObjectsToJsonMapper() {
        TestProcessState state = new TestProcessState();

        Map<String, Object> result = state.convertData(new TestPayload("web"));

        assertEquals("web", result.get("name"));
    }

    private static class TestProcessState extends AbstractStatesBasedProcessState {
        TestProcessState() {
            super(new JacksonJsonMapper(), null);
        }

        @Override
        protected boolean setState(boolean transitioning, String oldState, String newState) {
            return true;
        }

        @Override
        public String getState() {
            return "active";
        }

        @Override
        protected Map<String, Object> convertMap(Map<?, ?> data) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : data.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }

        @Override
        public String getResourceId() {
            return "1";
        }

        @Override
        public Object getResource() {
            return null;
        }

        @Override
        public io.cattle.platform.lock.definition.LockDefinition getProcessLock() {
            return null;
        }

        @Override
        public Map<String, Object> getData() {
            return null;
        }

        @Override
        public void reload() {
        }

        @Override
        public void applyData(Map<String, Object> data) {
        }

        @Override
        public void rebuild() {
        }
    }

    private static class TestPayload {
        private final String name;

        TestPayload(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }
}
