package io.cattle.platform.agent.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class AgentUncheckedBoundaryTest {

    @Test
    public void stringObjectMapCopiesStringKeysInOrderAndPreservesValues() {
        Map<String, Object> source = new LinkedHashMap<String, Object>();
        source.put("id", 42L);
        source.put("name", "agent-instance");

        Map<String, Object> result = AgentLocatorImpl.stringObjectMap(source);

        assertEquals(source, result);
        assertEquals(new ArrayList<String>(source.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void stringObjectMapRejectsNonStringKeys() {
        Map<Object, Object> source = new LinkedHashMap<Object, Object>();
        source.put(42L, "agent-instance");

        AgentLocatorImpl.stringObjectMap(source);
    }

    @Test
    public void responseWrapsNonDelegatedResultEvent() {
        Event request = EventVO.newEvent("agent.request").withReplyTo("agent.reply");
        Event result = EventVO.newEvent("agent.result").withData("payload");

        Event response = WrappedEventService.response(result, request, false, Event.class);

        assertEquals("agent.reply", response.getName());
        assertSame(result, response.getData());
    }

    @Test
    public void responseWrapsDelegatedPayload() {
        Event request = EventVO.newEvent("agent.request").withReplyTo("agent.reply");
        Event result = EventVO.newEvent("agent.result").withData("payload");

        Event response = WrappedEventService.response(result, request, true, Event.class);

        assertEquals("agent.reply", response.getName());
        assertEquals("payload", response.getData());
    }
}
