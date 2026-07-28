package io.cattle.platform.servicediscovery.process;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ServiceUpdateActivateTest {

    @Test
    public void consumedServiceIdLongConvertsIntegerToLong() {
        assertEquals(Long.valueOf(42L), ServiceUpdateActivate.consumedServiceIdLong(Integer.valueOf(42)));
    }

    @Test(expected = NullPointerException.class)
    public void consumedServiceIdLongPreservesNullFailureMode() {
        ServiceUpdateActivate.consumedServiceIdLong(null);
    }

    @Test(expected = ClassCastException.class)
    public void consumedServiceIdLongRejectsNonIntegerValues() {
        ServiceUpdateActivate.consumedServiceIdLong(Long.valueOf(42L));
    }
}
