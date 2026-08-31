package io.cattle.platform.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceLogRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;

import java.util.Date;

import org.junit.Test;

public class ActivityServiceTest {

    @Test
    public void populateInstanceLogBindsServiceAndInstanceIdentity() {
        ServiceRecord service = new ServiceRecord();
        service.setId(42L);
        service.setAccountId(5L);
        InstanceRecord instance = new InstanceRecord();
        instance.setId(99L);
        ServiceLogRecord log = new ServiceLogRecord();
        Date timestamp = new Date(123456789L);

        ActivityService.populateInstanceLog(log, service, instance, "restart", "Restarting container web-1",
                ActivityLog.INFO, timestamp, "transaction-1");

        assertEquals(Long.valueOf(5L), log.getAccountId());
        assertEquals(Long.valueOf(42L), log.getServiceId());
        assertEquals(Long.valueOf(99L), log.getInstanceId());
        assertEquals("service.instance.restart", log.getEventType());
        assertEquals("Restarting container web-1", log.getDescription());
        assertEquals(ActivityLog.INFO, log.getLevel());
        assertEquals(timestamp, log.getCreated());
        assertEquals(timestamp, log.getEndTime());
        assertEquals("transaction-1", log.getTransactionId());
        assertFalse(log.getSubLog());
    }
}
