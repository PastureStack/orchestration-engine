package io.cattle.platform.iaas.api.port;

import static io.cattle.platform.core.model.tables.AgentTable.AGENT;
import static io.cattle.platform.core.model.tables.HostTable.HOST;
import static io.cattle.platform.core.model.tables.InstanceHostMapTable.INSTANCE_HOST_MAP;
import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.PortTable.PORT;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import static io.cattle.platform.core.model.tables.StackTable.STACK;

import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.NetworkConstants;
import io.cattle.platform.core.constants.PortConstants;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.docker.constants.DockerInstanceConstants;
import io.cattle.platform.object.util.DataAccessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jooq.Record;

public class PortPreflightDaoImpl extends AbstractJooqDao implements PortPreflightDao {

    @Override
    public List<Host> getEligibleHosts(long accountId) {
        return create()
                .select(HOST.fields())
                .from(HOST)
                .leftOuterJoin(AGENT).on(AGENT.ID.eq(HOST.AGENT_ID))
                .where(HOST.ACCOUNT_ID.eq(accountId)
                        .and(HOST.REMOVED.isNull())
                        .and(HOST.STATE.in(CommonStatesConstants.ACTIVATING,
                                CommonStatesConstants.ACTIVE,
                                CommonStatesConstants.UPDATING_ACTIVE))
                        .and(AGENT.ID.isNull().or(AGENT.STATE.in(CommonStatesConstants.ACTIVE,
                                AgentConstants.STATE_FINISHING_RECONNECT,
                                AgentConstants.STATE_RECONNECTED))))
                .fetchInto(Host.class);
    }

    @Override
    public List<PortOwner> getPortOwners(long accountId) {
        Map<String, PortOwner> owners = new LinkedHashMap<String, PortOwner>();

        for (Record record : create()
                .select(PORT.fields())
                .select(INSTANCE_HOST_MAP.HOST_ID, HOST.NAME,
                        INSTANCE.ID, INSTANCE.NAME, INSTANCE.EXTERNAL_ID, INSTANCE.STATE, INSTANCE.STACK_ID, INSTANCE.DATA,
                        SERVICE.ID, SERVICE.NAME, SERVICE.STACK_ID,
                        STACK.ID, STACK.NAME)
                .from(PORT)
                .join(INSTANCE_HOST_MAP).on(INSTANCE_HOST_MAP.INSTANCE_ID.eq(PORT.INSTANCE_ID))
                .join(INSTANCE).on(INSTANCE.ID.eq(PORT.INSTANCE_ID))
                .join(HOST).on(HOST.ID.eq(INSTANCE_HOST_MAP.HOST_ID))
                .leftOuterJoin(SERVICE_EXPOSE_MAP)
                    .on(SERVICE_EXPOSE_MAP.INSTANCE_ID.eq(INSTANCE.ID)
                            .and(SERVICE_EXPOSE_MAP.REMOVED.isNull()))
                .leftOuterJoin(SERVICE).on(SERVICE.ID.eq(SERVICE_EXPOSE_MAP.SERVICE_ID))
                .leftOuterJoin(STACK)
                    .on(STACK.ID.eq(SERVICE.STACK_ID)
                            .or(SERVICE.ID.isNull().and(STACK.ID.eq(INSTANCE.STACK_ID))))
                .where(PORT.ACCOUNT_ID.eq(accountId)
                        .and(PORT.REMOVED.isNull())
                        .and(INSTANCE.REMOVED.isNull())
                        .and(INSTANCE_HOST_MAP.REMOVED.isNull())
                        .and(HOST.REMOVED.isNull()))) {
            Integer publicPort = record.get(PORT.PUBLIC_PORT);
            Integer privatePort = record.get(PORT.PRIVATE_PORT);
            String networkMode = DataAccessor.fromMap(record.get(INSTANCE.DATA))
                    .withKey(DockerInstanceConstants.FIELD_NETWORK_MODE).as(String.class);
            boolean hostNetwork = NetworkConstants.NETWORK_MODE_HOST.equalsIgnoreCase(networkMode);
            if ((!hostNetwork && publicPort == null) || (hostNetwork && privatePort == null)) {
                continue;
            }

            PortOwner owner = new PortOwner();
            owner.portId = record.get(PORT.ID);
            owner.hostId = record.get(INSTANCE_HOST_MAP.HOST_ID);
            owner.hostName = record.get(HOST.NAME);
            owner.instanceId = record.get(INSTANCE.ID);
            owner.instanceName = record.get(INSTANCE.NAME);
            owner.externalId = record.get(INSTANCE.EXTERNAL_ID);
            owner.state = record.get(INSTANCE.STATE);
            owner.serviceId = record.get(SERVICE.ID);
            owner.serviceName = record.get(SERVICE.NAME);
            owner.stackId = record.get(STACK.ID);
            owner.stackName = record.get(STACK.NAME);
            owner.privatePort = privatePort;
            if (hostNetwork) {
                owner.bindAddress = "0.0.0.0";
                owner.publicPort = owner.privatePort;
            } else {
                owner.bindAddress = DataAccessor.fromMap(record.get(PORT.DATA))
                        .withKey(PortConstants.FIELD_BIND_ADDR).as(String.class);
                owner.publicPort = publicPort;
            }
            owner.protocol = record.get(PORT.PROTOCOL);
            owners.put(owner.hostId + ":" + owner.instanceId + ":" + owner.portId, owner);
        }

        return new ArrayList<PortOwner>(owners.values());
    }
}
