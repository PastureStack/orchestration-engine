package io.cattle.platform.process.driver;

import static io.cattle.platform.core.model.tables.ServiceTable.*;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.addon.InServiceUpgradeStrategy;
import io.cattle.platform.core.addon.LbConfig;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.dao.LoadBalancerInfoDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.util.PortSpec;
import io.cattle.platform.core.util.SystemLabels;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.resource.ResourceMonitor;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.process.common.handler.AbstractObjectProcessHandler;
import io.cattle.platform.process.common.util.ProcessUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;


@Named
public class EnvironmentUpgrade extends AbstractObjectProcessHandler {
    private static final ConfigProperty<String> LB_IMAGE_UUID = ArchaiusUtil.getStringProperty("lb.instance.image.uuid");

    @Inject
    LoadBalancerInfoDao lbDao;
    @Inject
    ObjectManager objMgr;
    @Inject
    JsonMapper jsonMapper;
    @Inject
    ResourceMonitor resourceMonitor;

    @Override
    public String[] getProcessNames() {
        return new String[] { "account.upgrade" };
    }

    @Override
    public HandlerResult handle(ProcessState state, ProcessInstance process) {
        HandlerResult result = new HandlerResult(AccountConstants.FIELD_VERSION, AccountConstants.ACCOUNT_VERSION.get());
        Account env = (Account) state.getResource();
        if (AccountConstants.PROJECT_KIND.equals(env.getKind())) {
            upgradeServices(env);
        }
        return result;
    }

    public void upgradeServices(Account env) {
        List<? extends Service> lbServices = objMgr.find(Service.class, SERVICE.REMOVED, null, SERVICE.ACCOUNT_ID,
                env.getId(), SERVICE.KIND, ServiceConstants.KIND_LOAD_BALANCER_SERVICE);
        List<Service> upgradeWaitList = new ArrayList<>();
        for (Service lbService : lbServices) {
            LbConfig lbConfig = DataAccessor.field(lbService, ServiceConstants.FIELD_LB_CONFIG, jsonMapper,
                    LbConfig.class);
            Map<String, Object> existingLaunchConfig = getLaunchConfig(DataAccessor.fields(lbService)
                    .withKey(ServiceConstants.FIELD_LAUNCH_CONFIG));
            Object image = existingLaunchConfig.get(InstanceConstants.FIELD_IMAGE_UUID);
            if (image != null && image.toString().equalsIgnoreCase(LB_IMAGE_UUID.get())) {
                upgradeWaitList.add(lbService);
                continue;
            } else {
                List<String> validUpgradeStates = Arrays.asList(CommonStatesConstants.ACTIVE,
                        CommonStatesConstants.INACTIVE, CommonStatesConstants.UPDATING_ACTIVE);
                if (validUpgradeStates.contains(lbService.getState())) {
                    // 1. set lbconfig/new launch config on the service
                    InServiceUpgradeStrategy strategy = getUpgradeStrategy(lbService);
                    lbConfig = lbDao.generateLBConfig(lbService);
                    Map<String, Object> params = new HashMap<>();
                    params.put(ServiceConstants.FIELD_LB_CONFIG, lbConfig);
                    params.put(ServiceConstants.FIELD_LAUNCH_CONFIG, strategy.getLaunchConfig());
                    lbService = objectManager.setFields(objectManager.reload(lbService), params);

                    // 2. call upgrade
                    Map<String, Object> upgradeParams = new HashMap<>();
                    upgradeParams.put(ServiceConstants.FIELD_IN_SERVICE_STRATEGY, strategy);
                    objectProcessManager.scheduleProcessInstanceAsync(ServiceConstants.PROCESS_SERVICE_UPGRADE,
                            lbService, ProcessUtils.chainInData(upgradeParams,
                                    ServiceConstants.PROCESS_SERVICE_UPGRADE,
                                    ServiceConstants.PROCESS_SERVICE_FINISH_UPGRADE));
                }
                upgradeWaitList.add(lbService);
            }
        }

        // wait for service to be upgraded
        for (Service service : upgradeWaitList) {
            Service reloaded = objectManager.reload(service);
            resourceMonitor.waitForState(reloaded, CommonStatesConstants.ACTIVE);
        }
    }

    protected InServiceUpgradeStrategy getUpgradeStrategy(Service service) {
        Map<String, Object> existingLaunchConfig = getLaunchConfig(DataAccessor.fields(service)
                .withKey(ServiceConstants.FIELD_LAUNCH_CONFIG));
        Map<String, Object> newLaunchConfig = new HashMap<>();
        newLaunchConfig.putAll(existingLaunchConfig);
        // set ports
        if (existingLaunchConfig.containsKey(InstanceConstants.FIELD_PORTS)) {
            List<String> newPorts = new ArrayList<>();
            for (String port : stringList(existingLaunchConfig.get(InstanceConstants.FIELD_PORTS))) {
                PortSpec spec = new PortSpec(port);
                spec.setPrivatePort(spec.getPublicPort());
                newPorts.add(spec.toSpec());
            }
            newLaunchConfig.put(InstanceConstants.FIELD_PORTS, newPorts);
        }
        // set image
        newLaunchConfig.put(InstanceConstants.FIELD_IMAGE_UUID, LB_IMAGE_UUID.get());
        // set labels
        Object labelsObj = existingLaunchConfig.get(InstanceConstants.FIELD_LABELS);
        Map<String, String> labels = labelMapForWrite(labelsObj);
        labels.put(SystemLabels.LABEL_AGENT_ROLE, AgentConstants.ENVIRONMENT_ADMIN_ROLE + ",agent");
        labels.put(SystemLabels.LABEL_AGENT_CREATE, "true");
        if (labelsObj != null) {
            existingLaunchConfig.put(InstanceConstants.FIELD_LABELS, labels);
        }
        newLaunchConfig.put(InstanceConstants.FIELD_LABELS, labels);
        // generate version
        String version = io.cattle.platform.util.resource.UUID.randomUUID().toString();
        newLaunchConfig.put(ServiceConstants.FIELD_VERSION, version);

        return new InServiceUpgradeStrategy(newLaunchConfig, new ArrayList<Object>(),
                existingLaunchConfig, new ArrayList<Object>(), false, 2000L, 1L);

    }

    static Map<String, Object> getLaunchConfig(DataAccessor accessor) {
        return launchConfig(accessor.get());
    }

    static Map<String, Object> launchConfig(Object value) {
        if (value == null) {
            return Collections.emptyMap();
        }

        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Map<?, ?> fields = Map.class.cast(value);
        for (Map.Entry<?, ?> field : fields.entrySet()) {
            result.put(String.class.cast(field.getKey()), field.getValue());
        }
        return result;
    }

    static List<String> stringList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<String>();
        List<?> values = List.class.cast(value);
        for (Object item : values) {
            result.add(String.class.cast(item));
        }
        return result;
    }

    static Map<String, String> labelMapForWrite(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value == null) {
            return result;
        }

        Map<?, ?> labels = Map.class.cast(value);
        for (Map.Entry<?, ?> label : labels.entrySet()) {
            result.put(String.class.cast(label.getKey()), String.class.cast(label.getValue()));
        }
        return result;
    }
}
