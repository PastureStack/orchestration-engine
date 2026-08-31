package io.cattle.platform.servicediscovery.process;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.model.tables.records.InstanceRecord;

import org.junit.Test;

public class ServiceInstanceRestartLogPreListenerTest {

    @Test
    public void restartDescriptionUsesHumanReadableContainerName() {
        InstanceRecord instance = new InstanceRecord();
        instance.setName("web-1");

        assertEquals("Restarting container web-1",
                ServiceInstanceRestartLogPreListener.restartDescription(instance));
    }

    @Test
    public void restartDescriptionHasSafeFallback() {
        InstanceRecord instance = new InstanceRecord();

        assertEquals("Restarting service container",
                ServiceInstanceRestartLogPreListener.restartDescription(instance));
    }
}
