package io.cattle.platform.iaas.event.delegate;

import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;

import java.util.Map;

public class DelegateEventData {

    Map<String, Object> instanceData;
    EventVO<?> event;

    public DelegateEventData() {
    }

    public DelegateEventData(Map<String, Object> instanceData, Event event) {
        this.instanceData = instanceData;
        if (event instanceof EventVO<?>) {
            this.event = (EventVO<?>) event;
        } else {
            this.event = new EventVO<Object>(event);
        }
    }

    public Map<String, Object> getInstanceData() {
        return instanceData;
    }

    public void setInstanceData(Map<String, Object> instanceData) {
        this.instanceData = instanceData;
    }

    public EventVO<?> getEvent() {
        return event;
    }

    public void setEvent(EventVO<?> event) {
        this.event = event;
    }

}
