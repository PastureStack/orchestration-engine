package io.cattle.platform.servicediscovery.process;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.constants.ServiceConstants;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class SelectorServiceCreatePostListenerTest {

    @Test
    public void oldSelectorLinkReturnsEmptyStringWhenMissing() {
        assertEquals("", SelectorServiceCreatePostListener.oldSelectorLink(null));
        assertEquals("", SelectorServiceCreatePostListener.oldSelectorLink(new HashMap<String, Object>()));
    }

    @Test
    public void oldSelectorLinkReturnsExistingValue() {
        Map<String, Object> old = new HashMap<String, Object>();
        old.put(ServiceConstants.FIELD_SELECTOR_LINK, "app=web");

        assertEquals("app=web", SelectorServiceCreatePostListener.oldSelectorLink(old));
    }

    @Test
    public void oldSelectorLinkPreservesToStringBehavior() {
        Map<String, Object> old = new HashMap<String, Object>();
        old.put(ServiceConstants.FIELD_SELECTOR_LINK, Integer.valueOf(42));

        assertEquals("42", SelectorServiceCreatePostListener.oldSelectorLink(old));
    }

    @Test(expected = ClassCastException.class)
    public void oldSelectorLinkRejectsNonMapOldData() {
        SelectorServiceCreatePostListener.oldSelectorLink("not-a-map");
    }
}
