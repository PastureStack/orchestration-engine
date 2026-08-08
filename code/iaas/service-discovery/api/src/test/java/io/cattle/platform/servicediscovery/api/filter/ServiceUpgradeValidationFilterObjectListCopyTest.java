package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class ServiceUpgradeValidationFilterObjectListCopyTest {

    @Test
    public void objectListCopyPreservesElementsInWritableCopy() {
        List<String> values = Arrays.asList("primary", "sidekick");

        List<Object> result = ServiceUpgradeValidationFilter.objectListCopy(values);
        result.add("extra");

        assertEquals(Arrays.asList("primary", "sidekick", "extra"), result);
        assertEquals(Arrays.asList("primary", "sidekick"), values);
    }

    @Test
    public void objectListCopyDoesNotReuseMutableInputList() {
        List<Object> values = new ArrayList<>();

        assertNotSame(values, ServiceUpgradeValidationFilter.objectListCopy(values));
    }

    @Test(expected = NullPointerException.class)
    public void objectListCopyPreservesNullFailureMode() {
        ServiceUpgradeValidationFilter.objectListCopy(null);
    }
}
