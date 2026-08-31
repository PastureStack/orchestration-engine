package io.cattle.platform.activity;

import io.cattle.platform.activity.impl.ActivityLogImpl;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceLog;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.ObjectUtils;

import java.util.Date;

import jakarta.inject.Inject;

import org.apache.cloudstack.managed.threadlocal.ManagedThreadLocal;

public class ActivityService {

    @Inject
    ObjectManager objectManager;
    @Inject
    EventService eventService;

    private static ManagedThreadLocal<ActivityLog> TL = new ManagedThreadLocal<ActivityLog>();

    public ActivityLog newLog() {
        ActivityLog log = TL.get();
        if (log == null) {
            log = new ActivityLogImpl(objectManager, eventService);
            TL.set(log);
        }
        return log;
    }

    public void info(String message, Object... args) {
        ActivityLog activityLog = TL.get();
        if (activityLog == null) {
            return;
        }
        activityLog.info(message, args);
    }

    public void run(Service service, String type, String message, Runnable run) {
        ActivityLog log = newLog();
        try (Entry entry = log.start(service, type, message)) {
            try {
                run.run();
            } catch (RuntimeException|Error e) {
                entry.exception(e);
                throw e;
            }
        }
    }

    public void instance(Instance instance, String operation, String reason, String level) {
        ActivityLog activityLog = TL.get();
        if (activityLog == null) {
            return;
        }
        activityLog.instance(instance, operation, reason, level);
    }

    /**
     * Records an instance operation even when it was initiated outside a service activity context,
     * such as a user restarting a service-managed container directly.
     */
    public void instance(Service service, Instance instance, String operation, String reason, String level) {
        if (service == null || instance == null) {
            return;
        }

        ServiceLog log = objectManager.newRecord(ServiceLog.class);
        populateInstanceLog(log, service, instance, operation, reason, level,
                new Date(), io.cattle.platform.util.resource.UUID.randomUUID().toString());
        objectManager.create(log);
        ObjectUtils.publishChanged(eventService, objectManager, log);
    }

    static void populateInstanceLog(ServiceLog log, Service service, Instance instance, String operation,
            String reason, String level, Date timestamp, String transactionId) {
        log.setAccountId(service.getAccountId());
        log.setServiceId(service.getId());
        log.setInstanceId(instance.getId());
        log.setEventType("service.instance." + operation);
        log.setDescription(reason);
        log.setLevel(level);
        log.setCreated(timestamp);
        log.setEndTime(timestamp);
        log.setTransactionId(transactionId);
        log.setSubLog(false);
    }
}
