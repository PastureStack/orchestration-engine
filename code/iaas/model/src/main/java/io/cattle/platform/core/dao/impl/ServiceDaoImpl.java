package io.cattle.platform.core.dao.impl;

import static io.cattle.platform.core.model.tables.CertificateTable.*;
import static io.cattle.platform.core.model.tables.HealthcheckInstanceHostMapTable.*;
import static io.cattle.platform.core.model.tables.HealthcheckInstanceTable.*;
import static io.cattle.platform.core.model.tables.HostTable.*;
import static io.cattle.platform.core.model.tables.InstanceTable.*;
import static io.cattle.platform.core.model.tables.InstanceHostMapTable.*;
import static io.cattle.platform.core.model.tables.ServiceConsumeMapTable.*;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.*;
import static io.cattle.platform.core.model.tables.ServiceIndexTable.*;
import static io.cattle.platform.core.model.tables.ServiceTable.*;
import static io.cattle.platform.core.model.tables.StackTable.*;

import io.cattle.platform.core.addon.HealthcheckState;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.LoadBalancerConstants;
import io.cattle.platform.core.dao.ServiceDao;
import io.cattle.platform.core.model.Certificate;
import io.cattle.platform.core.model.HealthcheckInstance;
import io.cattle.platform.core.model.HealthcheckInstanceHostMap;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceIndex;
import io.cattle.platform.core.model.tables.HealthcheckInstanceHostMapTable;
import io.cattle.platform.core.model.tables.HostTable;
import io.cattle.platform.core.model.tables.InstanceTable;
import io.cattle.platform.core.model.tables.records.HealthcheckInstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.db.jooq.mapper.MultiRecordMapper;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;

import io.github.ibuildthecloud.gdapi.id.IdFormatter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Record2;
import org.jooq.Record3;
import org.jooq.impl.DSL;

@Named
public class ServiceDaoImpl extends AbstractJooqDao implements ServiceDao {

    @Inject
    ObjectManager objectManager;
    @Inject
    JsonMapper jsonMapper;

    @Override
    public Service getServiceByExternalId(Long accountId, String externalId) {
        return create().selectFrom(SERVICE)
                .where(SERVICE.ACCOUNT_ID.eq(accountId))
                .and(SERVICE.REMOVED.isNull())
                .and(SERVICE.EXTERNAL_ID.eq(externalId))
                .fetchAny();
    }

    @Override
    public ServiceIndex createServiceIndex(Service service, String launchConfigName, String serviceIndex) {
        ServiceIndex serviceIndexObj = objectManager.findAny(ServiceIndex.class, SERVICE_INDEX.SERVICE_ID,
                service.getId(),
                SERVICE_INDEX.LAUNCH_CONFIG_NAME, launchConfigName, SERVICE_INDEX.SERVICE_INDEX_, serviceIndex,
                SERVICE_INDEX.REMOVED, null);
        if (serviceIndexObj == null) {
            serviceIndexObj = objectManager.create(ServiceIndex.class, SERVICE_INDEX.SERVICE_ID,
                    service.getId(),
                    SERVICE_INDEX.LAUNCH_CONFIG_NAME, launchConfigName, SERVICE_INDEX.SERVICE_INDEX_, serviceIndex,
                    SERVICE_INDEX.ACCOUNT_ID, service.getAccountId());
        }
        return serviceIndexObj;
    }

    @Override
    public Service getServiceByServiceIndexId(long serviceIndexId) {
        Record record = create()
                .select(SERVICE.fields())
                .from(SERVICE)
                .join(SERVICE_INDEX).on(SERVICE.ID.eq(SERVICE_INDEX.SERVICE_ID))
                .where(SERVICE_INDEX.ID.eq(serviceIndexId))
                .fetchAny();

        return record == null ? null : record.into(Service.class);
    }

    @Override
    public boolean isServiceInstance(Instance instance) {
        return instance.getDeploymentUnitUuid() != null;
    }

    @Override
    public Map<Long, List<Object>> getServicesForInstances(List<Long> ids, final IdFormatter idFormatter) {
        final Map<Long, List<Object>> result = new HashMap<>();
        for (Record2<Long, Long> record : create().select(SERVICE_EXPOSE_MAP.INSTANCE_ID, SERVICE_EXPOSE_MAP.SERVICE_ID)
            .from(SERVICE_EXPOSE_MAP)
            .join(SERVICE)
                .on(SERVICE.ID.eq(SERVICE_EXPOSE_MAP.SERVICE_ID))
            .where(SERVICE_EXPOSE_MAP.REMOVED.isNull()
                    .and(SERVICE.REMOVED.isNull())
                    .and(SERVICE_EXPOSE_MAP.INSTANCE_ID.in(ids)))) {
            Long serviceId = record.getValue(SERVICE_EXPOSE_MAP.SERVICE_ID);
            Long instanceId = record.getValue(SERVICE_EXPOSE_MAP.INSTANCE_ID);
            List<Object> list = result.get(instanceId);
            if (list == null) {
                list = new ArrayList<>();
                result.put(instanceId, list);
            }
            list.add(idFormatter.formatId("service", serviceId));
        }

        return result;
    }

    @Override
    public List<Long> getUsageServiceIds(long accountId, long instanceId, String instanceUuid) {
        return usageServices(accountId, instanceId, instanceUuid).orderBy(SERVICE_EXPOSE_MAP.SERVICE_ID.asc())
                .limit(2).fetch(SERVICE_EXPOSE_MAP.SERVICE_ID);
    }

    private org.jooq.SelectConditionStep<Record1<Long>> usageServices(long accountId, long instanceId, String instanceUuid) {
        // The same native SEM relation as serviceIds, including stopped/inactive
        // resources. Two distinct Services mean unknown, not a hidden count.
        return create().selectDistinct(SERVICE_EXPOSE_MAP.SERVICE_ID).from(SERVICE_EXPOSE_MAP)
                .join(SERVICE).on(SERVICE.ID.eq(SERVICE_EXPOSE_MAP.SERVICE_ID))
                .join(INSTANCE).on(INSTANCE.ID.eq(SERVICE_EXPOSE_MAP.INSTANCE_ID))
                .where(INSTANCE.ID.eq(instanceId)).and(INSTANCE.UUID.eq(instanceUuid))
                .and(INSTANCE.ACCOUNT_ID.eq(accountId)).and(INSTANCE.KIND.eq("container")).and(INSTANCE.REMOVED.isNull())
                .and(SERVICE_EXPOSE_MAP.ACCOUNT_ID.eq(accountId)).and(SERVICE_EXPOSE_MAP.REMOVED.isNull())
                .and(SERVICE.ACCOUNT_ID.eq(accountId)).and(SERVICE.REMOVED.isNull());
    }

    @Override
    public boolean isUsageServiceCurrent(long accountId, long instanceId, String instanceUuid, long serviceId, long stackId,
            Long hostId, Long hostMappingId) {
        if ((hostId == null) != (hostMappingId == null)) return false;
        var current = usageServices(accountId, instanceId, instanceUuid).and(INSTANCE.STACK_ID.isNull())
                .and(SERVICE.ID.eq(serviceId)).and(SERVICE.STACK_ID.eq(stackId))
                .andExists(DSL.selectOne().from(STACK).where(STACK.ID.eq(stackId))
                        .and(STACK.ACCOUNT_ID.eq(accountId)).and(STACK.REMOVED.isNull()));
        current.and(DSL.field(DSL.selectCount().from(usageServices(accountId, instanceId, instanceUuid).limit(2).asTable("bindings"))).eq(1));
        if (hostId != null) current.andExists(DSL.selectOne().from(INSTANCE_HOST_MAP).join(HOST).on(HOST.ID.eq(INSTANCE_HOST_MAP.HOST_ID))
                .where(INSTANCE_HOST_MAP.ID.eq(hostMappingId)).and(INSTANCE_HOST_MAP.INSTANCE_ID.eq(instanceId))
                .and(HOST.ID.eq(hostId)).and(HOST.ACCOUNT_ID.eq(accountId)).and(HOST.REMOVED.isNull())
                .and(INSTANCE_HOST_MAP.REMOVED.isNull()));
        return create().fetchExists(current);
    }

    @Override
    public Map<Long, List<Object>> getInstances(List<Long> ids, final IdFormatter idFormatter) {
        final Map<Long, List<Object>> result = new HashMap<>();
        for (Record2<Long, Long> record : create().select(SERVICE_EXPOSE_MAP.INSTANCE_ID, SERVICE_EXPOSE_MAP.SERVICE_ID)
            .from(SERVICE_EXPOSE_MAP)
            .join(INSTANCE)
                .on(INSTANCE.ID.eq(SERVICE_EXPOSE_MAP.INSTANCE_ID))
            .where(SERVICE_EXPOSE_MAP.REMOVED.isNull()
                    .and(INSTANCE.REMOVED.isNull())
                    .and(SERVICE_EXPOSE_MAP.SERVICE_ID.in(ids))
                    .and(SERVICE_EXPOSE_MAP.REMOVED.isNull()))) {
            Long serviceId = record.getValue(SERVICE_EXPOSE_MAP.SERVICE_ID);
            Long instanceId = record.getValue(SERVICE_EXPOSE_MAP.INSTANCE_ID);
            List<Object> list = result.get(serviceId);
            if (list == null) {
                list = new ArrayList<>();
                result.put(serviceId, list);
            }
            list.add(idFormatter.formatId(InstanceConstants.TYPE, instanceId));
        }
        return result;
    }

    @Override
    public Map<Long, List<ServiceLink>> getServiceLinks(List<Long> ids) {
        final Map<Long, List<ServiceLink>> result = new HashMap<>();
        create().select(SERVICE_CONSUME_MAP.NAME, SERVICE_CONSUME_MAP.SERVICE_ID, SERVICE_CONSUME_MAP.CONSUMED_SERVICE, SERVICE.ID,
                SERVICE.NAME, STACK.ID, STACK.NAME)
            .from(SERVICE_CONSUME_MAP)
                .leftOuterJoin(SERVICE)
                .on(SERVICE.ID.eq(SERVICE_CONSUME_MAP.CONSUMED_SERVICE_ID))
                .leftOuterJoin(STACK)
                .on(STACK.ID.eq(SERVICE.STACK_ID))
            .where(SERVICE_CONSUME_MAP.SERVICE_ID.in(ids)
                    .and(SERVICE_CONSUME_MAP.REMOVED.isNull()))
                .forEach(record -> {
                    Long serviceId = record.getValue(SERVICE_CONSUME_MAP.SERVICE_ID);
                    List<ServiceLink> links = result.get(serviceId);
                    if (links == null) {
                        links = new ArrayList<>();
                        result.put(serviceId, links);
                    }
                    
                    String consumedServiceName = (record.getValue(SERVICE_CONSUME_MAP.CONSUMED_SERVICE)) != null
                            ? record.getValue(SERVICE_CONSUME_MAP.CONSUMED_SERVICE) : record.getValue(SERVICE.NAME);
                    
                    links.add(new ServiceLink(
                                record.getValue(SERVICE_CONSUME_MAP.NAME),
                                consumedServiceName,
                                record.getValue(SERVICE.ID),
                                record.getValue(STACK.ID),
                                record.getValue(STACK.NAME)));
            });
        return result;
    }

    @Override
    public List<GraphLink> getStackGraphLinks(long accountId, long stackId, List<Long> authorizedSourceIds) {
        if (accountId <= 0 || stackId <= 0 || authorizedSourceIds == null || authorizedSourceIds.size() > 16
                || authorizedSourceIds.stream().anyMatch(id -> id == null || id <= 0)) throw new IllegalArgumentException("Invalid graph source");
        if (authorizedSourceIds.isEmpty()) return List.of();
        var source = SERVICE.as("graph_source");
        var target = SERVICE.as("graph_target");
        return create().select(SERVICE_CONSUME_MAP.SERVICE_ID, SERVICE_CONSUME_MAP.CONSUMED_SERVICE_ID)
                .from(SERVICE_CONSUME_MAP)
                .join(source).on(source.ID.eq(SERVICE_CONSUME_MAP.SERVICE_ID))
                .join(target).on(target.ID.eq(SERVICE_CONSUME_MAP.CONSUMED_SERVICE_ID))
                .where(SERVICE_CONSUME_MAP.SERVICE_ID.in(authorizedSourceIds)
                        .and(SERVICE_CONSUME_MAP.ACCOUNT_ID.eq(accountId)).and(SERVICE_CONSUME_MAP.REMOVED.isNull())
                        .and(source.ACCOUNT_ID.eq(accountId)).and(source.STACK_ID.eq(stackId))
                        .and(source.KIND.eq("service")).and(source.REMOVED.isNull())
                        .and(target.ACCOUNT_ID.eq(accountId)).and(target.KIND.eq("service")).and(target.REMOVED.isNull()))
                .orderBy(SERVICE_CONSUME_MAP.ID.asc()).limit(33)
                .fetch(row -> new GraphLink(row.get(SERVICE_CONSUME_MAP.SERVICE_ID), row.get(SERVICE_CONSUME_MAP.CONSUMED_SERVICE_ID)));
    }

    @Override
    public List<Certificate> getLoadBalancerServiceCertificates(Service lbService) {
        List<? extends Long> certIds = DataAccessor.fields(lbService)
                .withKey(LoadBalancerConstants.FIELD_LB_CERTIFICATE_IDS).withDefault(Collections.EMPTY_LIST)
                .asList(jsonMapper, Long.class);
        Long defaultCertId = DataAccessor.fieldLong(lbService, LoadBalancerConstants.FIELD_LB_DEFAULT_CERTIFICATE_ID);
        List<Long> allCertIds = new ArrayList<>();
        allCertIds.addAll(certIds);
        allCertIds.add(defaultCertId);
        return create()
                .select(CERTIFICATE.fields())
                .from(CERTIFICATE)
                .where(CERTIFICATE.REMOVED.isNull())
                .and(CERTIFICATE.ID.in(allCertIds))
                .fetchInto(Certificate.class);
    }

    @Override
    public Certificate getLoadBalancerServiceDefaultCertificate(Service lbService) {
        Long defaultCertId = DataAccessor.fieldLong(lbService, LoadBalancerConstants.FIELD_LB_DEFAULT_CERTIFICATE_ID);
        List<? extends Certificate> certs = create()
                .select(CERTIFICATE.fields())
                .from(CERTIFICATE)
                .where(CERTIFICATE.REMOVED.isNull())
                .and(CERTIFICATE.ID.eq(defaultCertId))
                .fetchInto(Certificate.class);
        if (certs.isEmpty()) {
            return null;
        }
        return certs.get(0);
    }

    @Override
    public HealthcheckInstanceHostMap getHealthCheckInstanceUUID(String hostUUID, String instanceUUID) {
        MultiRecordMapper<HealthcheckInstanceHostMap> mapper = new MultiRecordMapper<HealthcheckInstanceHostMap>() {
            @Override
            protected HealthcheckInstanceHostMap map(List<Object> input) {
                if (input.get(0) != null) {
                    return (HealthcheckInstanceHostMap) input.get(0);
                }
                return null;
            }
        };

        HealthcheckInstanceHostMapTable hostMap = mapper.add(HEALTHCHECK_INSTANCE_HOST_MAP,
                HealthcheckInstanceHostMapTable.class);
        InstanceTable instance = mapper.add(INSTANCE, InstanceTable.class, INSTANCE.UUID, INSTANCE.ID);
        HostTable host = mapper.add(HOST, HostTable.class, HOST.UUID, HOST.ID);
        List<HealthcheckInstanceHostMap> maps = create()
                .select(mapper.fields())
                .from(hostMap)
                .join(instance)
                .on(instance.ID.eq(hostMap.INSTANCE_ID))
                .join(host)
                .on(host.ID.eq(hostMap.HOST_ID))
                .where(host.UUID.eq(hostUUID))
                .and(instance.UUID.eq(instanceUUID))
                .and(hostMap.REMOVED.isNull())
                .fetch().map(mapper);

        if (maps.size() == 0) {
            return null;
        }
        return maps.get(0);
    }


    @Override
    public Map<Long, List<HealthcheckState>> getHealthcheckStatesForInstances(List<Long> ids,
            final IdFormatter idFormatter) {
        final Map<Long, List<HealthcheckState>> result = new HashMap<>();
        for (Record3<Long, Long, String> record : create().select(HEALTHCHECK_INSTANCE_HOST_MAP.INSTANCE_ID, HEALTHCHECK_INSTANCE_HOST_MAP.HOST_ID,
                HEALTHCHECK_INSTANCE_HOST_MAP.HEALTH_STATE)
                .from(HEALTHCHECK_INSTANCE_HOST_MAP)
                .join(HOST)
                .on(HOST.ID.eq(HEALTHCHECK_INSTANCE_HOST_MAP.HOST_ID))
                .where(HEALTHCHECK_INSTANCE_HOST_MAP.REMOVED.isNull()
                        .and(HOST.REMOVED.isNull())
                        .and(HEALTHCHECK_INSTANCE_HOST_MAP.INSTANCE_ID.in(ids)))) {
            Long instanceId = record.getValue(HEALTHCHECK_INSTANCE_HOST_MAP.INSTANCE_ID);
            Long hostId = record.getValue(HEALTHCHECK_INSTANCE_HOST_MAP.HOST_ID);
            String healthState = record.getValue(HEALTHCHECK_INSTANCE_HOST_MAP.HEALTH_STATE);
            List<HealthcheckState> list = result.get(instanceId);
            if (list == null) {
                list = new ArrayList<>();
                result.put(instanceId, list);
            }
            HealthcheckState state = new HealthcheckState(idFormatter.formatId("host", hostId).toString(), healthState);
            list.add(state);
        }

        return result;
    }

    @Override
    public List<? extends HealthcheckInstance> findBadHealthcheckInstance(int limit) {
        return create()
                .select(HEALTHCHECK_INSTANCE.fields())
                .from(HEALTHCHECK_INSTANCE)
                .join(INSTANCE)
                    .on(INSTANCE.ID.eq(HEALTHCHECK_INSTANCE.INSTANCE_ID))
                .where(INSTANCE.STATE.eq(CommonStatesConstants.PURGED)
                        .and(HEALTHCHECK_INSTANCE.REMOVED.isNull())
                        .and(HEALTHCHECK_INSTANCE.STATE.notIn(CommonStatesConstants.DEACTIVATING,
                                CommonStatesConstants.REMOVING)))
                .limit(limit)
                .fetchInto(HealthcheckInstanceRecord.class);
    }

    @Override
    public List<? extends Service> getSkipServices(long accountId) {
        return create()
                .select(SERVICE.fields())
                .from(SERVICE)
                .where(SERVICE.ACCOUNT_ID.eq(accountId)
                        .and(SERVICE.REMOVED.isNull())
                        .and(SERVICE.SKIP.isTrue()))
                .fetchInto(ServiceRecord.class);
    }

}
