package io.cattle.platform.iaas.api.change.impl;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.object.meta.ObjectMetaDataManager;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.junit.Test;

public class ResourceChangeEventListenerImplTest {

    @Test
    public void addUsesAccountIdFromEventData() {
        ResourceChangeEventListenerImpl listener = new ResourceChangeEventListenerImpl();
        Map<String, Object> data = new HashMap<>();
        data.put(ObjectMetaDataManager.ACCOUNT_FIELD, Long.valueOf(42));

        listener.add(EventVO.<Map<String, Object>>newEvent("resource.change")
                .withResourceType("instance")
                .withResourceId("1")
                .withData(data));

        assertEquals(Long.valueOf(42), listener.changed.get(new ImmutablePair<String, String>("instance", "1")));
    }

    @Test
    public void addUsesGlobalMarkerWhenEventDataIsNull() {
        ResourceChangeEventListenerImpl listener = new ResourceChangeEventListenerImpl();

        listener.add(EventVO.newEvent("resource.change")
                .withResourceType("instance")
                .withResourceId("1"));

        assertEquals(Boolean.TRUE, listener.changed.get(new ImmutablePair<String, String>("instance", "1")));
    }

    @Test(expected = ClassCastException.class)
    public void addRejectsNonMapEventData() {
        ResourceChangeEventListenerImpl listener = new ResourceChangeEventListenerImpl();

        listener.add(EventVO.newEvent("resource.change")
                .withResourceType("instance")
                .withResourceId("1")
                .withData("invalid"));
    }
}
