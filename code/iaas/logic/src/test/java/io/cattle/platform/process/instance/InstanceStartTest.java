package io.cattle.platform.process.instance;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.object.util.DataAccessor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InstanceStartTest {

    private final InstanceStart instanceStart = new InstanceStart();

    @Test
    public void normalizePrimaryIpAddressKeepsPlainAddress() {
        assertEquals("10.42.182.209", instanceStart.normalizePrimaryIpAddress("10.42.182.209"));
    }

    @Test
    public void normalizePrimaryIpAddressStripsCidr() {
        assertEquals("10.42.182.209", instanceStart.normalizePrimaryIpAddress("10.42.182.209/16"));
    }

    @Test
    public void normalizePrimaryIpAddressRejectsBlankInput() {
        assertNull(instanceStart.normalizePrimaryIpAddress("  "));
        assertNull(instanceStart.normalizePrimaryIpAddress(null));
    }

    @Test
    public void shouldPersistPrimaryIpAddressWhenMissing() {
        InstanceRecord instance = new InstanceRecord();

        assertTrue(instanceStart.shouldPersistPrimaryIpAddress(instance, "10.42.182.209"));
    }

    @Test
    public void shouldNotPersistPrimaryIpAddressWhenUnchanged() {
        InstanceRecord instance = new InstanceRecord();
        DataAccessor.setField(instance, InstanceConstants.FIELD_PRIMARY_IP_ADDRESS, "10.42.182.209");

        assertFalse(instanceStart.shouldPersistPrimaryIpAddress(instance, "10.42.182.209"));
        assertFalse(instanceStart.shouldPersistPrimaryIpAddress(instance, " "));
        assertFalse(instanceStart.shouldPersistPrimaryIpAddress(instance, null));
    }
}
