package io.cattle.platform.engine.process;

import io.cattle.platform.engine.manager.ProcessManager;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.lock.LockManager;

import java.util.List;
import java.util.Collections;

public class ProcessServiceContext {

    LockManager lockManager;
    EventService eventService;
    ProcessManager processManager;
    ExecutionExceptionHandler exceptionHandler;
    List<StateChangeMonitor> changeMonitors;
    List<ProcessAuthorizationHook> authorizationHooks = Collections.emptyList();

    public ProcessServiceContext(LockManager lockManager, EventService eventService, ProcessManager processManager, ExecutionExceptionHandler exceptionHandler,
            List<StateChangeMonitor> changeMonitors) {
        super();
        this.lockManager = lockManager;
        this.eventService = eventService;
        this.processManager = processManager;
        this.exceptionHandler = exceptionHandler;
        this.changeMonitors = changeMonitors;
    }

    public LockManager getLockManager() {
        return lockManager;
    }

    public EventService getEventService() {
        return eventService;
    }

    public ProcessManager getProcessManager() {
        return processManager;
    }

    public ExecutionExceptionHandler getExceptionHandler() {
        return exceptionHandler;
    }

    public List<StateChangeMonitor> getChangeMonitors() {
        return changeMonitors;
    }

    public List<ProcessAuthorizationHook> getAuthorizationHooks() { return authorizationHooks; }

    public void setAuthorizationHooks(List<ProcessAuthorizationHook> hooks) { authorizationHooks = hooks; }

}
