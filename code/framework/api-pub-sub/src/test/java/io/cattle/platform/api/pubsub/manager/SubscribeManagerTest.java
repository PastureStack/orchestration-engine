package io.cattle.platform.api.pubsub.manager;

import static org.junit.Assert.assertEquals;

import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class SubscribeManagerTest {

    @Test
    public void eventNamesReadDirectlyFromConditions() {
        SubscribeManager manager = new SubscribeManager();
        Map<String, List<Condition>> conditions = new HashMap<String, List<Condition>>();
        conditions.put("eventNames", Arrays.asList(
                new Condition(ConditionType.EQ, "resource.change"),
                new Condition(ConditionType.EQ, "instance.start")));

        List<String> eventNames = manager.eventNamesFromConditions(conditions);

        assertEquals(Arrays.asList("resource.change", "instance.start"), eventNames);
    }
}
