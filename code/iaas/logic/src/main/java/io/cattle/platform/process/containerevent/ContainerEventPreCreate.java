package io.cattle.platform.process.containerevent;

import static io.cattle.platform.core.model.tables.AgentTable.*;
import static io.cattle.platform.core.model.tables.ContainerEventTable.*;
import static io.cattle.platform.process.containerevent.ContainerEventCreate.*;

import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.dao.AgentDao;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.ContainerEvent;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.engine.handler.ProcessPreListener;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.process.common.handler.AbstractObjectProcessLogic;
import io.cattle.platform.util.type.Priority;

import java.util.HashMap;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Named
public class ContainerEventPreCreate extends AbstractObjectProcessLogic implements ProcessPreListener, Priority {

    private static final Logger log = LoggerFactory.getLogger(ContainerEventPreCreate.class);

    @Inject
    AgentDao agentDao;

    @Override
    public String[] getProcessNames() {
        return new String[] { "containerevent.create" };
    }

    @Override
    public HandlerResult handle(ProcessState state, ProcessInstance process) {
        return resolve((ContainerEvent)state.getResource(), state.getData());
    }

    HandlerResult resolve(ContainerEvent event, Map<String, Object> stateData) {
        // event's account id is set to the agent that submitted. This will
        // change it to the actual user's account id.
        // Checks to make sure agent's resource account id and the host's
        // account id match
        Agent agent = objectManager.findAny(Agent.class, AGENT.ACCOUNT_ID, event.getAccountId());

        Long resourceAccId = null;
        Host host = resolveHost(event, agent);
        if (host == null) {
            log.warn("Ignoring container event {} because host {} was not found", event.getId(), event.getHostId());
            return null;
        }

        if ( agent != null ) {
            resourceAccId = DataAccessor.fromDataFieldOf(agent)
                    .withKey(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID).as(Long.class);
        } else if ( event.getAccountId() != null && event.getAccountId().equals(host.getAccountId()) ){
            resourceAccId = event.getAccountId();
        }

        if ( host.getAccountId().equals(resourceAccId) ) {
            Map<Object, Object> newFields = new HashMap<Object, Object>();
            newFields.put(CONTAINER_EVENT.ACCOUNT_ID, host.getAccountId());
            newFields.put(CONTAINER_EVENT.HOST_ID, host.getId());
            DataAccessor.fromMap(stateData).withScope(ContainerEventCreate.class).withKey(AGENT_ID).set(host.getAgentId());
            return new HandlerResult(newFields);
        }

        return null;
    }

    Host resolveHost(ContainerEvent event, Agent agent) {
        Host host = objectManager.loadResource(Host.class, event.getHostId());
        if (host != null) {
            return host;
        }

        if (agent == null || event.getReportedHostUuid() == null) {
            return null;
        }

        Map<String, Host> hosts = agentDao.getHosts(agent.getId());
        return hosts.get(event.getReportedHostUuid());
    }

    @Override
    public int getPriority() {
        return Priority.DEFAULT;
    }
}
