package io.cattle.platform.iaas.api.filter.serviceevent;


import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.dao.AgentDao;
import io.cattle.platform.core.dao.ServiceDao;
import io.cattle.platform.core.dao.InstanceDao;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.HealthcheckInstance;
import io.cattle.platform.core.model.HealthcheckInstanceHostMap;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceEvent;
import io.cattle.platform.iaas.api.filter.common.AbstractDefaultResourceManagerFilter;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;

import java.util.Arrays;
import java.util.List;

import jakarta.inject.Inject;

import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ServiceEventFilter extends AbstractDefaultResourceManagerFilter {

    private static final Logger log = LoggerFactory.getLogger(ServiceEventFilter.class);
    private static List<String> invalidStates = Arrays.asList(CommonStatesConstants.REMOVED, CommonStatesConstants.REMOVING);
    private static final ConfigProperty<Boolean> ENABLE_HEALTHCHECK = ArchaiusUtil.getBooleanProperty("ipsec.service.enable.healthcheck");
    private static final ConfigProperty<Integer> SERVICE_EVENT_CREATE_RETRIES = ArchaiusUtil.getIntProperty("service.event.create.retry.count", 1);
    private static final ConfigProperty<Long> SERVICE_EVENT_CREATE_RETRY_DELAY = ArchaiusUtil.getLongProperty("service.event.create.retry.delay.millis", 25L);
    private static List<String> upgradingStates = Arrays.asList(
            ServiceConstants.STATE_UPGRADING,
            ServiceConstants.STATE_UPGRADED,
            ServiceConstants.STATE_ROLLINGBACK,
            ServiceConstants.STATE_CANCELING_UPGRADE,
            ServiceConstants.STATE_CANCELED_UPGRADE,
            ServiceConstants.STATE_FINISHING_UPGRADE);
    public static final String VERIFY_AGENT = "CantVerifyHealthcheck";

    @Inject
    ObjectManager objectManager;

    @Inject
    AgentDao agentDao;

    @Inject
    ServiceDao serviceDao;

    @Inject
    InstanceDao instanceDao;

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[] { ServiceEvent.class };
    }

    protected Agent getAgent() {
        Agent agent = objectManager.loadResource(Agent.class, ApiUtils.getPolicy().getOption(Policy.AGENT_ID));
        if (agent == null) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN, VERIFY_AGENT);
        }

        return agentDao.getHostAgentForDelegate(agent.getId());
    }

    @Override
    public Object create(String type, ApiRequest request, ResourceManager next) {
        ServiceEvent event = request.proxyRequestObject(ServiceEvent.class);

        /* Will never return null, MissingRequired will be thrown if missing */
        Agent agent = getAgent();
        if (agent == null) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN, VERIFY_AGENT);
        }


        HealthcheckInstanceHostMap healthcheckInstanceHostMap = null;
        String[] splitted = event.getHealthcheckUuid().split("_");
        if (splitted.length > 2) {
            healthcheckInstanceHostMap = serviceDao.getHealthCheckInstanceUUID(splitted[0], splitted[1]);
        } else {
            healthcheckInstanceHostMap = objectManager.findOne(HealthcheckInstanceHostMap.class,
                    ObjectMetaDataManager.UUID_FIELD, splitted[0]);
        }

        if (healthcheckInstanceHostMap == null) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN, VERIFY_AGENT);
        }

        HealthcheckInstance healthcheckInstance = objectManager.loadResource(HealthcheckInstance.class,
                healthcheckInstanceHostMap.getHealthcheckInstanceId());

        if (healthcheckInstance == null) {
            return null;
        }
        Long resourceAccId = DataAccessor.fromDataFieldOf(agent)
                .withKey(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID)
                .as(Long.class);

        if (!healthcheckInstanceHostMap.getAccountId().equals(resourceAccId)) {
            throw new ClientVisibleException(ResponseCodes.FORBIDDEN, VERIFY_AGENT);
        }
        if(isNetworkStack(resourceAccId, healthcheckInstance.getInstanceId())) {
                if(!ENABLE_HEALTHCHECK.get()) {
                    event.setReportedHealth("UP");
                }
        } else {
            if (event.getReportedHealth().startsWith("DOWN") && isNetworkUpgrading(resourceAccId)) {
                throw new ClientVisibleException(ResponseCodes.CONFLICT);
            }
        }

        event.setInstanceId(healthcheckInstance.getInstanceId());
        event.setHealthcheckInstanceId(healthcheckInstance.getId());
        event.setHostId(healthcheckInstanceHostMap.getHostId());

        int retries = Math.max(0, SERVICE_EVENT_CREATE_RETRIES.get());
        for (int attempt = 0;; attempt++) {
            try {
                return super.create(type, request, next);
            } catch (DataAccessException e) {
                if (attempt >= retries || !isTransientServiceEventWrite(e)) {
                    throw e;
                }

                log.info("Retrying service event create after transient database conflict, attempt {}/{}: {}",
                        attempt + 1, retries, e.getMessage());
                pauseBeforeServiceEventRetry(e);
            }
        }
    }

    protected boolean isTransientServiceEventWrite(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null
                    && message.contains("Record has changed since last read")
                    && message.contains("service_event")) {
                return true;
            }
        }

        return false;
    }

    private void pauseBeforeServiceEventRetry(DataAccessException e) {
        long delay = SERVICE_EVENT_CREATE_RETRY_DELAY.get();
        if (delay <= 0) {
            return;
        }

        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    private boolean isNetworkUpgrading(long accountId) {
            Service networkDriverService = null;
        List<Service> networkDriverServices = objectManager.find(Service.class, SERVICE.ACCOUNT_ID, accountId, SERVICE.REMOVED, null, SERVICE.KIND,
                ServiceConstants.KIND_NETWORK_DRIVER_SERVICE);
        for(Service service : networkDriverServices) {
                if(invalidStates.contains(service.getState())) {
                    continue;
                }
                networkDriverService = service;
        }
        if (networkDriverService == null) {
            return false;
        }
        List<Service> services = objectManager.find(Service.class, SERVICE.ACCOUNT_ID, accountId, SERVICE.REMOVED, null, SERVICE.STACK_ID,
                networkDriverService.getStackId());
        for (Service service : services) {
            if (upgradingStates.contains(service.getState())) {
                return true;
            }
        }
        return false;
    }

    private boolean isNetworkStack(long accountId, long instanceId) {
        List<? extends Service> services = instanceDao.findServicesForInstanceId(instanceId);
        if(services.size() > 0) {
            if(services.get(0).getKind().equals(ServiceConstants.KIND_NETWORK_DRIVER_SERVICE)) {
                return true;
            }
            List<Service> network_services = objectManager.find(Service.class, SERVICE.ACCOUNT_ID, accountId, SERVICE.REMOVED, null, SERVICE.STACK_ID,
                    services.get(0).getStackId(), SERVICE.KIND, ServiceConstants.KIND_NETWORK_DRIVER_SERVICE);
            if(network_services.size() > 0) {
                return true;
            }
        }
        return false;
    }
}
