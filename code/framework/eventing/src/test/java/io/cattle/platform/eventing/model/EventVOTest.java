package io.cattle.platform.eventing.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class EventVOTest {

    @Test
    public void copyConstructorPreservesErasedDataViewAndOverridesReplyTo() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("instanceId", "1i1");

        Event source = EventVO.<Map<String, Object>>newEvent("compute.instance.activate")
                .withData(data)
                .withReplyTo("old.reply");

        EventVO<Map<String, Object>> copy = new EventVO<Map<String, Object>>(source, "new.reply");

        assertEquals("compute.instance.activate", copy.getName());
        assertEquals("new.reply", copy.getReplyTo());
        assertSame(data, copy.getData());
        assertEquals("1i1", copy.getData().get("instanceId"));
    }

    @Test
    public void copyConstructorDoesNotEagerlyValidateErasedDataType() {
        Object data = new Object();
        Event source = EventVO.<Object>newEvent("delegate.test")
                .withData(data)
                .withReplyTo("old.reply");

        EventVO<Map<String, Object>> copy = new EventVO<Map<String, Object>>(source, "new.reply");

        assertEquals("new.reply", copy.getReplyTo());
        assertSame(data, ((Event) copy).getData());
    }

    @Test
    public void erasedDataCastBoundaryStaysPrivate() throws Exception {
        Object data = new Object();
        Event source = EventVO.<Object>newEvent("delegate.test").withData(data);
        Method method = EventVO.class.getDeclaredMethod("eventData", Event.class);

        assertTrue(Modifier.isPrivate(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        method.setAccessible(true);
        assertSame(data, method.invoke(null, source));
    }
}
