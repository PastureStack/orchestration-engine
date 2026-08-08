package io.cattle.platform.iaas.event.delegate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class DelegateEventDataTest {

    @Test
    public void keepsEventVoInstanceWithoutUncheckedCast() {
        Map<String, Object> instanceData = new HashMap<String, Object>();
        EventVO<String> event = EventVO.<String>newEvent("delegate.test").withData("payload");

        DelegateEventData data = new DelegateEventData(instanceData, event);

        assertSame(instanceData, data.getInstanceData());
        assertSame(event, data.getEvent());
        assertEquals("payload", data.getEvent().getData());
    }

    @Test
    public void wrapsPlainEventAsObjectEventVo() {
        EventVO<String> event = EventVO.<String>newEvent("delegate.test").withData("payload");

        DelegateEventData data = new DelegateEventData(new HashMap<String, Object>(), new PlainEvent(event));

        assertEquals("delegate.test", data.getEvent().getName());
        assertEquals("payload", data.getEvent().getData());
    }

    private static class PlainEvent implements Event {
        private final Event source;

        PlainEvent(EventVO<String> event) {
            source = event;
        }

        @Override
        public String getId() {
            return source.getId();
        }

        @Override
        public String getName() {
            return source.getName();
        }

        @Override
        public String getReplyTo() {
            return source.getReplyTo();
        }

        @Override
        public String getResourceId() {
            return source.getResourceId();
        }

        @Override
        public String getResourceType() {
            return source.getResourceType();
        }

        @Override
        public String[] getPreviousIds() {
            return source.getPreviousIds();
        }

        @Override
        public String[] getPreviousNames() {
            return source.getPreviousNames();
        }

        @Override
        public String getTransitioning() {
            return source.getTransitioning();
        }

        @Override
        public Integer getTransitioningProgress() {
            return source.getTransitioningProgress();
        }

        @Override
        public String getTransitioningMessage() {
            return source.getTransitioningMessage();
        }

        @Override
        public String getTransitioningInternalMessage() {
            return source.getTransitioningInternalMessage();
        }

        @Override
        public Object getData() {
            return source.getData();
        }

        @Override
        public Date getTime() {
            return source.getTime();
        }

        @Override
        public Long getTimeoutMillis() {
            return source.getTimeoutMillis();
        }

        @Override
        public String getPublisher() {
            return source.getPublisher();
        }

        @Override
        public Map<String, Object> getContext() {
            return source.getContext();
        }
    }
}
