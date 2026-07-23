package io.cattle.platform.agent.connection.simulator.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.util.type.CollectionUtils;

import java.util.Map;

import org.junit.Test;

public class SimulatorStartStopProcessorTest {

    @Test
    public void instanceMapReadsNestedEventInstanceData() {
        SimulatorStartStopProcessor processor = new SimulatorStartStopProcessor();
        EventVO<Object> event = EventVO.newEvent("compute.instance.activate").withData(CollectionUtils.asMap(
                "instanceHostMap", CollectionUtils.asMap(
                        "instance", CollectionUtils.asMap(
                                "uuid", "instance-1",
                                "externalId", "docker-id-1"))));

        Map<String, Object> result = processor.instanceMap(event);

        assertEquals("instance-1", result.get("uuid"));
        assertEquals("docker-id-1", result.get("externalId"));
    }

    @Test
    public void stringObjectMapPreservesNullBoundary() {
        SimulatorStartStopProcessor processor = new SimulatorStartStopProcessor();

        assertNull(processor.stringObjectMap(null));
    }
}
