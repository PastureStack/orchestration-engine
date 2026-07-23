package io.cattle.platform.servicediscovery.api.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.allocator.service.AllocationHelper;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.util.type.CollectionUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryUtilBuildLaunchDataTest {

    @Test
    public void buildServiceInstanceLaunchDataMergesLabelsThroughAllocationHelper() {
        RecordingAllocationHelper allocationHelper = new RecordingAllocationHelper();
        Map<String, String> serviceLabels = labels("io.rancher.service", "true");
        Map<String, String> deployLabels = labels("io.rancher.deploy", "true");
        Service service = serviceWithPrimaryLaunchConfig(launchConfigWithLabels(serviceLabels));
        Map<String, Object> deployParams = new HashMap<>();
        deployParams.put(InstanceConstants.FIELD_LABELS, deployLabels);

        Map<String, Object> result = ServiceDiscoveryUtil.buildServiceInstanceLaunchData(service, deployParams,
                ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME, allocationHelper.proxy());

        Map<Object, Object> labels = CollectionUtils.toMap(result.get(InstanceConstants.FIELD_LABELS));
        assertEquals("true", labels.get("io.rancher.service"));
        assertEquals("true", labels.get("io.rancher.deploy"));
        assertEquals(1, allocationHelper.normalizeCalls);
        assertEquals(1, allocationHelper.mergeCalls);
        assertEquals(Long.valueOf(13L), result.get("accountId"));
        assertEquals(InstanceConstants.KIND_CONTAINER, result.get(ObjectMetaDataManager.KIND_FIELD));
    }

    @Test
    public void buildServiceInstanceLaunchDataMergesMapAndListValues() {
        Map<String, Object> serviceEnv = new HashMap<>();
        serviceEnv.put("SERVICE_ONLY", "1");
        Map<String, Object> serviceLaunchConfig = new HashMap<>();
        serviceLaunchConfig.put("environment", serviceEnv);
        serviceLaunchConfig.put(InstanceConstants.FIELD_PORTS, Arrays.asList("80/tcp"));

        Map<String, Object> deployEnv = new HashMap<>();
        deployEnv.put("DEPLOY_ONLY", "1");
        Map<String, Object> deployParams = new HashMap<>();
        deployParams.put("environment", deployEnv);
        deployParams.put(InstanceConstants.FIELD_PORTS, Arrays.asList("80/tcp", "443/tcp"));

        Map<String, Object> result = ServiceDiscoveryUtil.buildServiceInstanceLaunchData(
                serviceWithPrimaryLaunchConfig(serviceLaunchConfig), deployParams,
                ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME, new RecordingAllocationHelper().proxy());

        Map<Object, Object> environment = CollectionUtils.toMap(result.get("environment"));
        assertEquals("1", environment.get("SERVICE_ONLY"));
        assertEquals("1", environment.get("DEPLOY_ONLY"));
        assertEquals(Arrays.asList("80/tcp", "443/tcp"), result.get(InstanceConstants.FIELD_PORTS));
    }

    @Test
    public void buildServiceInstanceLaunchDataDoesNotOverwriteExistingKind() {
        Map<String, Object> serviceLaunchConfig = new HashMap<>();
        serviceLaunchConfig.put(ObjectMetaDataManager.KIND_FIELD, "customKind");

        Map<String, Object> result = ServiceDiscoveryUtil.buildServiceInstanceLaunchData(
                serviceWithPrimaryLaunchConfig(serviceLaunchConfig), null, ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME,
                new RecordingAllocationHelper().proxy());

        assertEquals("customKind", result.get(ObjectMetaDataManager.KIND_FIELD));
    }

    @Test(expected = ClassCastException.class)
    public void buildServiceInstanceLaunchDataRejectsNonMapDeployValueForServiceMap() {
        Map<String, Object> serviceLaunchConfig = new HashMap<>();
        serviceLaunchConfig.put("environment", new HashMap<String, Object>());
        Map<String, Object> deployParams = new HashMap<>();
        deployParams.put("environment", "not-a-map");

        ServiceDiscoveryUtil.buildServiceInstanceLaunchData(serviceWithPrimaryLaunchConfig(serviceLaunchConfig),
                deployParams, ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME, new RecordingAllocationHelper().proxy());
    }

    @Test(expected = ClassCastException.class)
    public void buildServiceInstanceLaunchDataRejectsNonListDeployValueForServiceList() {
        Map<String, Object> serviceLaunchConfig = new HashMap<>();
        serviceLaunchConfig.put(InstanceConstants.FIELD_PORTS, Arrays.asList("80/tcp"));
        Map<String, Object> deployParams = new HashMap<>();
        deployParams.put(InstanceConstants.FIELD_PORTS, "not-a-list");

        ServiceDiscoveryUtil.buildServiceInstanceLaunchData(serviceWithPrimaryLaunchConfig(serviceLaunchConfig),
                deployParams, ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME, new RecordingAllocationHelper().proxy());
    }

    @Test
    public void launchConfigObjectListCopyCreatesWritableCopy() {
        assertTrue(ServiceDiscoveryUtil.launchConfigObjectListCopy(Arrays.asList()).isEmpty());
    }

    private static Map<String, String> labels(String key, String value) {
        Map<String, String> labels = new HashMap<>();
        labels.put(key, value);
        return labels;
    }

    private static Map<String, Object> launchConfigWithLabels(Map<String, String> labels) {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put(InstanceConstants.FIELD_LABELS, labels);
        return launchConfig;
    }

    private static Service serviceWithPrimaryLaunchConfig(Map<String, Object> primaryLaunchConfig) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(ServiceConstants.FIELD_LAUNCH_CONFIG, primaryLaunchConfig);

        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, fields);

        ServiceRecord service = new ServiceRecord();
        service.setAccountId(13L);
        service.setStackId(7L);
        service.setData(data);
        return service;
    }

    private static final class RecordingAllocationHelper implements InvocationHandler {
        int mergeCalls;
        int normalizeCalls;

        AllocationHelper proxy() {
            return AllocationHelper.class.cast(Proxy.newProxyInstance(AllocationHelper.class.getClassLoader(),
                    new Class<?>[] { AllocationHelper.class }, this));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if ("normalizeLabels".equals(method.getName())) {
                normalizeCalls++;
                assertEquals(Long.valueOf(7L), args[0]);
                return null;
            }
            if ("mergeLabels".equals(method.getName())) {
                mergeCalls++;
                Map<?, ?> source = Map.class.cast(args[0]);
                Map<Object, Object> destination = CollectionUtils.toMap(args[1]);
                for (Map.Entry<?, ?> entry : source.entrySet()) {
                    destination.put(String.class.cast(entry.getKey()), String.class.cast(entry.getValue()));
                }
                return null;
            }
            if ("toString".equals(method.getName())) {
                return getClass().getSimpleName();
            }
            throw new UnsupportedOperationException(method.getName());
        }
    }
}
