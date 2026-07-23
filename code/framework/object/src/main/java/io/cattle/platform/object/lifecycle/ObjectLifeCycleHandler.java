package io.cattle.platform.object.lifecycle;

import java.util.Map;

public interface ObjectLifeCycleHandler {

    public enum LifeCycleEvent {
        CREATE, UPDATE, DELETE
    }

    public <T> T onEvent(LifeCycleEvent event, T instance, Class<?> clz, Map<String, Object> properties);

}
