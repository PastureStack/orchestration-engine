package io.cattle.iaas.healthcheck.process;

import static io.cattle.platform.core.model.tables.AgentTable.*;

import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.dao.AgentDao;
import io.cattle.platform.core.dao.ServiceDao;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.HealthcheckInstance;
import io.cattle.platform.core.model.HealthcheckInstanceHostMap;
import io.cattle.platform.core.model.ServiceEvent;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.engine.handler.ProcessPreListener;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.process.common.handler.AbstractObjectProcessLogic;
import io.cattle.platform.util.type.Priority;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;
import jakarta.inject.Named;

@Named
public class ServiceEventPreCreate extends AbstractObjectProcessLogic implements ProcessPreListener, Priority {

    @Inject
    ServiceDao serviceDao;

    @Inject
    AgentDao agentDao;

    @Override
    public String[] getProcessNames() {
        return new String[] { "serviceevent.create" };
    }

    @Override
    public HandlerResult handle(ProcessState state, ProcessInstance process) {
        return resolve((ServiceEvent)state.getResource());
    }

    HandlerResult resolve(ServiceEvent event) {
        HealthcheckInstanceHostMap hostMap = resolveHostMap(event);
        boolean resolvedFromAgentPayload = event.getHealthcheckInstanceId() == null && hostMap != null;
        if (resolvedFromAgentPayload && !isAuthorizedDelegate(event, hostMap)) {
            return null;
        }

        HealthcheckInstance hcInstance = objectManager.loadResource(HealthcheckInstance.class, event.getHealthcheckInstanceId());

        if (hcInstance == null && hostMap != null) {
            hcInstance = objectManager.loadResource(HealthcheckInstance.class, hostMap.getHealthcheckInstanceId());
        }

        if (hcInstance != null) {
            Map<Object, Object> data = new HashMap<Object, Object>();
            data.put(ObjectMetaDataManager.ACCOUNT_FIELD, hcInstance.getAccountId());
            if (hostMapMatchesHealthcheck(hostMap, hcInstance)) {
                data.put("hostId", hostMap.getHostId());
                data.put("instanceId", hcInstance.getInstanceId());
                data.put("healthcheckInstanceId", hcInstance.getId());
            }
            return new HandlerResult(data);
        }

        return null;
    }

    boolean hostMapMatchesHealthcheck(HealthcheckInstanceHostMap hostMap, HealthcheckInstance hcInstance) {
        return hostMap != null
                && Objects.equals(hostMap.getHealthcheckInstanceId(), hcInstance.getId())
                && Objects.equals(hostMap.getAccountId(), hcInstance.getAccountId());
    }

    HealthcheckInstanceHostMap resolveHostMap(ServiceEvent event) {
        if (event.getHealthcheckInstanceId() == null && event.getHealthcheckUuid() == null) {
            return null;
        }

        if (event.getHealthcheckUuid() == null) {
            return null;
        }

        String[] splitted = event.getHealthcheckUuid().split("_");
        if (splitted.length > 2) {
            return serviceDao.getHealthCheckInstanceUUID(splitted[0], splitted[1]);
        }

        return objectManager.findOne(HealthcheckInstanceHostMap.class,
                ObjectMetaDataManager.UUID_FIELD, splitted[0]);
    }

    boolean isAuthorizedDelegate(ServiceEvent event, HealthcheckInstanceHostMap hostMap) {
        Agent delegateAgent = objectManager.findAny(Agent.class, AGENT.ACCOUNT_ID, event.getAccountId());
        if (delegateAgent == null) {
            return false;
        }

        Agent hostAgent = agentDao.getHostAgentForDelegate(delegateAgent.getId());
        if (hostAgent == null) {
            return false;
        }

        Long resourceAccountId = DataAccessor.fromDataFieldOf(hostAgent)
                .withKey(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID).as(Long.class);
        return Objects.equals(hostMap.getAccountId(), resourceAccountId);
    }

    @Override
    public int getPriority() {
        return Priority.DEFAULT;
    }
}
