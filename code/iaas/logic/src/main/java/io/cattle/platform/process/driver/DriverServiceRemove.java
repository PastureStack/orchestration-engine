package io.cattle.platform.process.driver;

import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.engine.handler.AbstractProcessLogic;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.engine.handler.ProcessPreListener;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.cattle.platform.object.util.ObjectUtils;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
public class DriverServiceRemove extends AbstractProcessLogic implements ProcessPreListener {

    @Inject
    ObjectManager objectManager;
    @Inject
    ObjectProcessManager processManager;

    @Override
    public String[] getProcessNames() {
        return new String[] { ServiceConstants.PROCESS_SERVICE_REMOVE };
    }

    @Override
    public HandlerResult handle(ProcessState state, ProcessInstance process) {
        Set<String> scheduled = new HashSet<>();
        for (String driverKey : DriverServiceActivate.DRIVERS) {
            Class<?> driverClass = objectManager.getSchemaFactory().getSchemaClass(driverKey);

            Service service = (Service)state.getResource();
            for (Object driver : objectManager.children(service, driverClass)) {
                if (ObjectUtils.getRemoved(driver) != null || Set.of(CommonStatesConstants.REMOVING, CommonStatesConstants.REMOVED,
                        CommonStatesConstants.PURGING, CommonStatesConstants.PURGED).contains(Objects.toString(ObjectUtils.getState(driver), ""))) {
                    continue;
                }
                if (!scheduled.add(driverClass.getName() + ":" + ObjectUtils.getId(driver))) {
                    continue;
                }
                processManager.scheduleStandardChainedProcessAsync(StandardProcess.DEACTIVATE, StandardProcess.REMOVE, driver, null);
            }
        }
        return null;
    }

}
