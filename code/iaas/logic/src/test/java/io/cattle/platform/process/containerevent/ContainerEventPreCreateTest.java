package io.cattle.platform.process.containerevent;

import static io.cattle.platform.core.model.tables.AgentTable.AGENT;
import static io.cattle.platform.core.model.tables.ContainerEventTable.CONTAINER_EVENT;
import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.dao.AgentDao;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.tables.records.AgentRecord;
import io.cattle.platform.core.model.tables.records.ContainerEventRecord;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.object.ObjectManager;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ContainerEventPreCreateTest {

    @Test
    public void resolvesHostIdForDirectAgentCreatedContainerEvents() {
        AgentRecord agent = new AgentRecord();
        agent.setId(11L);
        agent.setAccountId(7L);
        Map<String, Object> agentData = new HashMap<String, Object>();
        agentData.put(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID, 5L);
        agent.setData(agentData);

        HostRecord host = new HostRecord();
        host.setId(3L);
        host.setAgentId(11L);
        host.setAccountId(5L);

        ContainerEventRecord event = new ContainerEventRecord();
        event.setId(99L);
        event.setAccountId(7L);
        event.setReportedHostUuid("reported-host");

        TestContainerEventPreCreate handler = new TestContainerEventPreCreate();
        handler.injectObjectManager(objectManager(agent, null));
        handler.agentDao = agentDao("reported-host", host);

        Map<String, Object> stateData = new HashMap<String, Object>();
        HandlerResult result = handler.resolve(event, stateData);

        assertEquals(5L, result.getData().get(CONTAINER_EVENT.ACCOUNT_ID));
        assertEquals(3L, result.getData().get(CONTAINER_EVENT.HOST_ID));
        assertEquals(11L, ((Map<?, ?>) stateData.get(ContainerEventCreate.class.getName()))
                .get(ContainerEventCreate.AGENT_ID));
    }

    static class TestContainerEventPreCreate extends ContainerEventPreCreate {
        void injectObjectManager(ObjectManager objectManager) {
            this.objectManager = objectManager;
        }
    }

    static ObjectManager objectManager(final Agent agent, final Host host) {
        return (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class },
                (proxy, method, args) -> {
                    if ("findAny".equals(method.getName()) && args[0] == Agent.class
                            && args[1] == AGENT.ACCOUNT_ID) {
                        return agent;
                    }
                    if ("loadResource".equals(method.getName()) && args[0] == Host.class) {
                        return host;
                    }
                    return null;
                });
    }

    static AgentDao agentDao(final String reportedHostUuid, final Host host) {
        return (AgentDao) Proxy.newProxyInstance(AgentDao.class.getClassLoader(),
                new Class<?>[] { AgentDao.class },
                (proxy, method, args) -> {
                    if ("getHosts".equals(method.getName())) {
                        return Collections.singletonMap(reportedHostUuid, host);
                    }
                    return null;
                });
    }
}
