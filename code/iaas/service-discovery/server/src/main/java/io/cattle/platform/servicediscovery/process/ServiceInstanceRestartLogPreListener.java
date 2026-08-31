package io.cattle.platform.servicediscovery.process;

import io.cattle.platform.activity.ActivityLog;
import io.cattle.platform.activity.ActivityService;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.dao.InstanceDao;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.engine.handler.ProcessPreListener;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.process.common.handler.AbstractObjectProcessLogic;
import io.cattle.platform.util.type.Priority;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;

@Named
public class ServiceInstanceRestartLogPreListener extends AbstractObjectProcessLogic implements ProcessPreListener, Priority {

    @Inject
    InstanceDao instanceDao;

    @Inject
    ActivityService activityService;

    @Override
    public HandlerResult handle(ProcessState state, ProcessInstance process) {
        Instance instance = (Instance) state.getResource();
        if (instance == null || instance.getId() == null) {
            return null;
        }

        List<? extends Service> services = instanceDao.findServicesForInstanceId(instance.getId());
        for (Service service : services) {
            activityService.instance(service, instance, "restart", restartDescription(instance), ActivityLog.INFO);
        }

        return null;
    }

    static String restartDescription(Instance instance) {
        String name = instance == null ? null : instance.getName();
        return StringUtils.isBlank(name) ? "Restarting service container" : "Restarting container " + name;
    }

    @Override
    public String[] getProcessNames() {
        return new String[] { InstanceConstants.PROCESS_RESTART };
    }

    @Override
    public int getPriority() {
        return Priority.DEFAULT;
    }
}
