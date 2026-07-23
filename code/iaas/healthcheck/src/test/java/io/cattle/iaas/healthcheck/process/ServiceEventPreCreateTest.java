package io.cattle.iaas.healthcheck.process;

import static io.cattle.platform.core.model.tables.AgentTable.AGENT;
import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.dao.AgentDao;
import io.cattle.platform.core.dao.ServiceDao;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.HealthcheckInstance;
import io.cattle.platform.core.model.HealthcheckInstanceHostMap;
import io.cattle.platform.core.model.tables.records.AgentRecord;
import io.cattle.platform.core.model.tables.records.HealthcheckInstanceHostMapRecord;
import io.cattle.platform.core.model.tables.records.HealthcheckInstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceEventRecord;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.meta.ObjectMetaDataManager;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceEventPreCreateTest {

    @Test
    public void resolvesDirectAgentCreatedServiceEventsFromHealthcheckUuid() {
        AgentRecord delegateAgent = new AgentRecord();
        delegateAgent.setId(21L);
        delegateAgent.setAccountId(409L);

        AgentRecord hostAgent = new AgentRecord();
        hostAgent.setId(1L);
        hostAgent.setAccountId(7L);
        Map<String, Object> hostAgentData = new HashMap<String, Object>();
        hostAgentData.put(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID, 5L);
        hostAgent.setData(hostAgentData);

        HealthcheckInstanceHostMapRecord hostMap = new HealthcheckInstanceHostMapRecord();
        hostMap.setId(31L);
        hostMap.setAccountId(5L);
        hostMap.setHostId(3L);
        hostMap.setHealthcheckInstanceId(41L);

        HealthcheckInstanceRecord healthcheckInstance = new HealthcheckInstanceRecord();
        healthcheckInstance.setId(41L);
        healthcheckInstance.setAccountId(5L);
        healthcheckInstance.setInstanceId(51L);

        ServiceEventRecord event = new ServiceEventRecord();
        event.setAccountId(409L);
        event.setHealthcheckUuid("host-reported_hc-map_1");

        TestServiceEventPreCreate handler = new TestServiceEventPreCreate();
        handler.injectObjectManager(objectManager(delegateAgent, healthcheckInstance));
        handler.agentDao = agentDao(hostAgent);
        handler.serviceDao = serviceDao(hostMap);

        HandlerResult result = handler.resolve(event);

        assertEquals(5L, result.getData().get(ObjectMetaDataManager.ACCOUNT_FIELD));
        assertEquals(3L, result.getData().get("hostId"));
        assertEquals(51L, result.getData().get("instanceId"));
        assertEquals(41L, result.getData().get("healthcheckInstanceId"));
    }

    @Test
    public void preservesAlreadyResolvedServiceEventsWithoutDelegateAgent() {
        HealthcheckInstanceHostMapRecord hostMap = new HealthcheckInstanceHostMapRecord();
        hostMap.setId(31L);
        hostMap.setAccountId(5L);
        hostMap.setHostId(3L);
        hostMap.setHealthcheckInstanceId(41L);

        HealthcheckInstanceRecord healthcheckInstance = new HealthcheckInstanceRecord();
        healthcheckInstance.setId(41L);
        healthcheckInstance.setAccountId(5L);
        healthcheckInstance.setInstanceId(51L);

        ServiceEventRecord event = new ServiceEventRecord();
        event.setAccountId(5L);
        event.setHealthcheckInstanceId(41L);
        event.setHealthcheckUuid("host-reported_hc-map_1");

        TestServiceEventPreCreate handler = new TestServiceEventPreCreate();
        handler.injectObjectManager(objectManager(null, healthcheckInstance));
        handler.agentDao = agentDao(null);
        handler.serviceDao = serviceDao(hostMap);

        HandlerResult result = handler.resolve(event);

        assertEquals(5L, result.getData().get(ObjectMetaDataManager.ACCOUNT_FIELD));
        assertEquals(3L, result.getData().get("hostId"));
        assertEquals(51L, result.getData().get("instanceId"));
        assertEquals(41L, result.getData().get("healthcheckInstanceId"));
    }

    static class TestServiceEventPreCreate extends ServiceEventPreCreate {
        void injectObjectManager(ObjectManager objectManager) {
            this.objectManager = objectManager;
        }
    }

    static ObjectManager objectManager(final Agent delegateAgent, final HealthcheckInstance healthcheckInstance) {
        return (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class },
                (proxy, method, args) -> {
                    if ("findAny".equals(method.getName()) && args[0] == Agent.class
                            && args[1] == AGENT.ACCOUNT_ID) {
                        return delegateAgent;
                    }
                    if ("loadResource".equals(method.getName()) && args[0] == HealthcheckInstance.class
                            && Long.valueOf(41L).equals(args[1])) {
                        return healthcheckInstance;
                    }
                    return null;
                });
    }

    static AgentDao agentDao(final Agent hostAgent) {
        return (AgentDao) Proxy.newProxyInstance(AgentDao.class.getClassLoader(),
                new Class<?>[] { AgentDao.class },
                (proxy, method, args) -> {
                    if ("getHostAgentForDelegate".equals(method.getName())) {
                        return hostAgent;
                    }
                    return null;
                });
    }

    static ServiceDao serviceDao(final HealthcheckInstanceHostMap hostMap) {
        return (ServiceDao) Proxy.newProxyInstance(ServiceDao.class.getClassLoader(),
                new Class<?>[] { ServiceDao.class },
                (proxy, method, args) -> {
                    if ("getHealthCheckInstanceUUID".equals(method.getName())) {
                        return hostMap;
                    }
                    return null;
                });
    }
}
