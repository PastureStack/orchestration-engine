package io.cattle.platform.servicediscovery.api.filter;

import io.cattle.platform.core.addon.InServiceUpgradeStrategy;
import io.cattle.platform.core.addon.ServiceUpgrade;
import io.cattle.platform.core.addon.ServiceUpgradeStrategy;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.iaas.api.filter.common.AbstractDefaultResourceManagerFilter;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.servicediscovery.api.util.ServiceDiscoveryUtil;
import io.cattle.platform.storage.api.filter.ExternalTemplateInstanceFilter;
import io.cattle.platform.storage.service.StorageService;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.tuple.Pair;

@Named
public class ServiceUpgradeValidationFilter extends AbstractDefaultResourceManagerFilter {

    @Inject
    ObjectManager objectManager;

    @Inject
    JsonMapper jsonMapper;

    @Inject
    StorageService storageService;

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[] { Service.class };
    }

    @Override
    public String[] getTypes() {
        List<String> supportedTypes = new ArrayList<>();
        supportedTypes.addAll(ServiceConstants.SERVICE_LIKE);
        supportedTypes.add(ServiceConstants.KIND_DNS_SERVICE);
        supportedTypes.add(ServiceConstants.KIND_EXTERNAL_SERVICE);
        return supportedTypes.toArray(new String[supportedTypes.size()]);
    }

    @Override
    public Object resourceAction(String type, ApiRequest request, ResourceManager next) {
        if (request.getAction().equals(ServiceConstants.ACTION_SERVICE_UPGRADE)) {
            Service service = objectManager.loadResource(Service.class, request.getId());
            ServiceUpgrade upgrade = jsonMapper.convertValue(request.getRequestObject(),
                    ServiceUpgrade.class);

            ServiceUpgradeStrategy strategy = upgrade.getStrategy();
            if (strategy == null) {
                ValidationErrorCodes.throwValidationError(ValidationErrorCodes.MISSING_REQUIRED,
                        "Upgrade strategy needs to be set");
            }

            processInServiceUpgradeStrategy(request, service, upgrade, strategy);
        }

        return super.resourceAction(type, request, next);
    }

    protected void processInServiceUpgradeStrategy(ApiRequest request, Service service, ServiceUpgrade upgrade,
            ServiceUpgradeStrategy strategy) {
        if (strategy instanceof InServiceUpgradeStrategy) {
            InServiceUpgradeStrategy inServiceStrategy = (InServiceUpgradeStrategy) strategy;
            inServiceStrategy = finalizeUpgradeStrategy(service, inServiceStrategy);

            Object launchConfig = DataAccessor.field(service, ServiceConstants.FIELD_LAUNCH_CONFIG,
                    Object.class);
            Object newLaunchConfig = inServiceStrategy.getLaunchConfig();
            if (newLaunchConfig != null) {
                ServiceDiscoveryUtil.validateScaleSwitch(newLaunchConfig, launchConfig);
            }
            List<?> secondaryLaunchConfigValues = DataAccessor.fields(service)
                    .withKey(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS)
                    .withDefault(Collections.emptyList()).as(
                            List.class);
            List<Object> secondaryLaunchConfigs = objectListCopy(secondaryLaunchConfigValues);
            inServiceStrategy.setPreviousLaunchConfig(launchConfig);
            inServiceStrategy.setPreviousSecondaryLaunchConfigs(secondaryLaunchConfigs);
            upgrade.setInServiceStrategy(inServiceStrategy);
            request.setRequestObject(jsonMapper.writeValueAsMap(upgrade));
            ServiceDiscoveryUtil.upgradeServiceConfigs(service, inServiceStrategy, false);
        }
        objectManager.persist(service);
    }

    static List<Object> objectListCopy(List<?> values) {
        return new ArrayList<Object>(values);
    }

    protected void setVersion(InServiceUpgradeStrategy upgrade) {
        String version = io.cattle.platform.util.resource.UUID.randomUUID().toString();
        if (upgrade.getSecondaryLaunchConfigs() != null) {
            for (Object launchConfigObj : upgrade.getSecondaryLaunchConfigs()) {
                setLaunchConfigVersion(version, launchConfigObj);
            }
        }
        if (upgrade.getLaunchConfig() != null) {
            setLaunchConfigVersion(version, upgrade.getLaunchConfig());
        }
    }

    protected void setLaunchConfigVersion(String version, Object launchConfigObj) {
        Map<Object, Object> launchConfig = launchConfigMap(launchConfigObj);
        launchConfig.put(ServiceConstants.FIELD_VERSION, version);
    }

    static Map<Object, Object> launchConfigMap(Object launchConfigObj) {
        Map.class.cast(launchConfigObj);
        if (launchConfigObj == null) {
            throw new NullPointerException();
        }
        return CollectionUtils.toMap(launchConfigObj);
    }

    protected InServiceUpgradeStrategy finalizeUpgradeStrategy(Service service, InServiceUpgradeStrategy strategy) {
        if (strategy.getLaunchConfig() == null && strategy.getSecondaryLaunchConfigs() == null) {
            ValidationErrorCodes.throwValidationError(ValidationErrorCodes.INVALID_OPTION,
                    "LaunchConfig/secondaryLaunchConfigs need to be specified for inService strategy");
        }

        if (DataAccessor.fieldBool(service, ServiceConstants.FIELD_SERVICE_RETAIN_IP)) {
            if (strategy.getStartFirst()) {
                ValidationErrorCodes.throwValidationError(ValidationErrorCodes.INVALID_OPTION,
                        "StartFirst option can't be used for service with "
                                + ServiceConstants.FIELD_SERVICE_RETAIN_IP + " field set");
            }
        }

        if (service.getKind().equalsIgnoreCase(ServiceConstants.KIND_LOAD_BALANCER_SERVICE)) {
            if (strategy.getLaunchConfig() == null) {
                ValidationErrorCodes.throwValidationError(ValidationErrorCodes.INVALID_OPTION,
                        "LaunchConfig is required for load balancer service");
            }
            ServiceDiscoveryUtil.injectBalancerLabelsAndHealthcheck(launchConfigMap(strategy.getLaunchConfig()));
        }

        Map<String, Map<Object, Object>> serviceLCs = getExistingLaunchConfigs(service);
        Map<String, Map<Object, Object>> lCsToUpdateInitial = getLaunchConfigsToUpdateInitial(service, strategy,
                serviceLCs);
        Map<String, Map<Object, Object>> lCsToUpdateFinal = getLaunchConfigsToUpdateFinal(serviceLCs,
                lCsToUpdateInitial);

        for (String name : lCsToUpdateFinal.keySet()) {

            if (!lCsToUpdateInitial.containsKey(name)) {
                Object launchConfig = lCsToUpdateFinal.get(name);
                if (name.equalsIgnoreCase(service.getName())) {
                    strategy.setLaunchConfig(launchConfig);
                } else {
                    List<Object> secondaryLCs = strategy.getSecondaryLaunchConfigs();
                    if (secondaryLCs == null) {
                        secondaryLCs = new ArrayList<>();
                    }
                    secondaryLCs.add(launchConfig);
                    strategy.setSecondaryLaunchConfigs(secondaryLCs);
                }
            }
        }

        // set new version on the configs-to-upgrade
        setVersion(strategy);

        // add launch configs that don't need to be upgraded
        // they will get saved with the old version value
        List<Object> finalizedSecondary = new ArrayList<>();
        if (strategy.getSecondaryLaunchConfigs() != null) {
            finalizedSecondary.addAll(strategy.getSecondaryLaunchConfigs());
        }
        Object finalizedPrimary = strategy.getLaunchConfig();
        Map<String, Map<Object, Object>> existingLCs = getExistingLaunchConfigs(service);
        for (String scName : existingLCs.keySet()) {
            if (!lCsToUpdateFinal.containsKey(scName)) {
                if (Strings.CS.equals(scName, service.getName())) {
                    finalizedPrimary = existingLCs.get(scName);
                } else {
                    finalizedSecondary.add(existingLCs.get(scName));
                }
            }
        }

        // remove secondary launch configs marked with labels
        Iterator<Object> it = finalizedSecondary.iterator();
        while (it.hasNext()) {
            Object lc = it.next();
            Map<String, Object> mapped = CollectionUtils.toMap(lc);
            Object imageUuid = mapped.get(InstanceConstants.FIELD_IMAGE_UUID);
            if (imageUuid == null) {
                continue;
            }

            if (service.getSelectorContainer() == null
                    && Strings.CI.equals(ServiceConstants.IMAGE_NONE, imageUuid.toString())) {
                it.remove();
            }
            else {
                String fullImageName = ExternalTemplateInstanceFilter.getImageUuid(imageUuid.toString(), storageService);
                putLaunchConfigImageUuid(lc, fullImageName);
            }
        }

        Map<String, Object> mapped = CollectionUtils.toMap(finalizedPrimary);
        Object imageUuid = mapped.get(InstanceConstants.FIELD_IMAGE_UUID);
        if (imageUuid != null && !imageUuid.toString().equalsIgnoreCase(ServiceConstants.IMAGE_NONE)) {
            String fullImageName = ExternalTemplateInstanceFilter.getImageUuid(imageUuid.toString(), storageService);
            putLaunchConfigImageUuid(finalizedPrimary, fullImageName);
        }

        strategy.setLaunchConfig(finalizedPrimary);
        strategy.setSecondaryLaunchConfigs(finalizedSecondary);

        return strategy;
    }

    static void putLaunchConfigImageUuid(Object launchConfigObj, String fullImageName) {
        Map.class.cast(launchConfigObj);
        if (launchConfigObj == null) {
            throw new NullPointerException();
        }
        CollectionUtils.toMap(launchConfigObj).put(InstanceConstants.FIELD_IMAGE_UUID, fullImageName);
    }

    protected Map<String, Map<Object, Object>> getLaunchConfigsToUpdateFinal(
            Map<String, Map<Object, Object>> serviceLCs,
            Map<String, Map<Object, Object>> lCsToUpdateInitial) {
        Map<String, Map<Object, Object>> lCsToUpdateFinal = new HashMap<>();
        for (String lcNameToUpdate : lCsToUpdateInitial.keySet()) {
            finalizeLCNamesToUpdate(serviceLCs, lCsToUpdateFinal,
                    Pair.of(lcNameToUpdate, lCsToUpdateInitial.get(lcNameToUpdate)));
        }
        return lCsToUpdateFinal;
    }

    protected Map<String, Map<Object, Object>> getLaunchConfigsToUpdateInitial(Service service,
            InServiceUpgradeStrategy strategy,
            Map<String, Map<Object, Object>> serviceLCs) {
        Map<String, Map<Object, Object>> lCsToUpdateInitial = new HashMap<>();
        if (strategy.getLaunchConfig() != null) {
            lCsToUpdateInitial.put(service.getName(), launchConfigMap(strategy.getLaunchConfig()));
        }

        if (strategy.getSecondaryLaunchConfigs() != null) {
            for (Object secondaryLC : strategy.getSecondaryLaunchConfigs()) {
                String lcName = CollectionUtils.toMap(secondaryLC).get("name").toString();
                lCsToUpdateInitial.put(lcName, launchConfigMap(secondaryLC));
            }
        }
        return lCsToUpdateInitial;
    }

    protected Map<String, Map<Object, Object>> getExistingLaunchConfigs(Service service) {
        Map<String, Map<Object, Object>> serviceLCs = ServiceDiscoveryUtil.getServiceLaunchConfigsWithNames(service);
        Map<Object, Object> primaryLC = serviceLCs.get(ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME);
        serviceLCs.remove(ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME);
        serviceLCs.put(service.getName(), primaryLC);
        return serviceLCs;
    }

    // this method finalizes volumeFrom/networkFrom dependencies that need to be updated as well
    protected void finalizeLCNamesToUpdate(Map<String, Map<Object, Object>> serviceLCs,
            Map<String, Map<Object, Object>> lCToUpdateFinal,
            Pair<String, Map<Object, Object>> lcToUpdate) {
        Map<Object, Object> finalConfig = new HashMap<>();
        finalConfig.putAll(lcToUpdate.getRight());
        lCToUpdateFinal.put(lcToUpdate.getLeft(), finalConfig);
        for (String serviceLCName : serviceLCs.keySet()) {
            Map<Object, Object> serviceLC = serviceLCs.get(serviceLCName);
            List<String> refs = new ArrayList<>();
            Object networkFromLaunchConfig = serviceLC
                    .get(ServiceConstants.FIELD_NETWORK_LAUNCH_CONFIG);
            if (networkFromLaunchConfig != null) {
                refs.add(String.class.cast(networkFromLaunchConfig));
            }
            Object volumesFromLaunchConfigs = serviceLC
                    .get(ServiceConstants.FIELD_DATA_VOLUMES_LAUNCH_CONFIG);
            if (volumesFromLaunchConfigs != null) {
                refs.addAll(stringList(volumesFromLaunchConfigs));
            }
            for (String ref : refs) {
                if (lcToUpdate.getLeft().equalsIgnoreCase(ref)) {
                    finalizeLCNamesToUpdate(serviceLCs, lCToUpdateFinal,
                            Pair.of(serviceLCName, serviceLC));
                }
            }
        }
    }

    static List<String> stringList(Object values) {
        List<?> items = List.class.cast(values);
        List<String> result = new ArrayList<>();
        for (Object item : items) {
            result.add(String.class.cast(item));
        }
        return result;
    }
}
