package io.cattle.platform.servicediscovery.api.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.allocator.service.AllocationHelper;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.util.type.CollectionUtils;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryUtilMergedServiceLabelsTest {

    @Test
    public void getMergedServiceLabelsMergesPrimaryAndSecondaryLabels() {
        RecordingAllocationHelper allocationHelper = new RecordingAllocationHelper();
        Map<String, String> primaryLabels = labels("io.rancher.primary", "true");
        Map<String, String> secondaryLabels = labels("io.rancher.secondary", "true");
        Service service = serviceWithLaunchConfigs(launchConfig(primaryLabels),
                Arrays.asList(secondary("sidekick", secondaryLabels)));

        Map<String, String> result = ServiceDiscoveryUtil.getMergedServiceLabels(service, allocationHelper.proxy());

        assertEquals("true", result.get("io.rancher.primary"));
        assertEquals("true", result.get("io.rancher.secondary"));
        assertEquals(2, allocationHelper.mergeCalls);
    }

    @Test
    public void getMergedServiceLabelsSkipsLaunchConfigsWithoutLabels() {
        RecordingAllocationHelper allocationHelper = new RecordingAllocationHelper();
        Service service = serviceWithLaunchConfigs(new HashMap<>(), Arrays.asList(secondary("sidekick", null)));

        Map<String, String> result = ServiceDiscoveryUtil.getMergedServiceLabels(service, allocationHelper.proxy());

        assertTrue(result.isEmpty());
        assertEquals(0, allocationHelper.mergeCalls);
    }

    @Test(expected = ClassCastException.class)
    public void getMergedServiceLabelsRejectsNonMapLabelsAtExistingLaunchConfigDataBoundary() {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put(InstanceConstants.FIELD_LABELS, "not-a-map");

        ServiceDiscoveryUtil.getMergedServiceLabels(serviceWithLaunchConfigs(launchConfig),
                new RecordingAllocationHelper().proxy());
    }

    private static Map<String, String> labels(String key, String value) {
        Map<String, String> labels = new HashMap<>();
        labels.put(key, value);
        return labels;
    }

    private static Map<String, Object> launchConfig(Map<String, String> labels) {
        Map<String, Object> launchConfig = new HashMap<>();
        if (labels != null) {
            launchConfig.put(InstanceConstants.FIELD_LABELS, labels);
        }
        return launchConfig;
    }

    private static Map<String, Object> secondary(String name, Map<String, String> labels) {
        Map<String, Object> launchConfig = launchConfig(labels);
        launchConfig.put("name", name);
        return launchConfig;
    }

    private static Service serviceWithLaunchConfigs(Map<String, Object> primaryLaunchConfig) {
        return serviceWithLaunchConfigs(primaryLaunchConfig, Collections.emptyList());
    }

    private static Service serviceWithLaunchConfigs(Map<String, Object> primaryLaunchConfig,
            List<Map<String, Object>> secondaryLaunchConfigs) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(ServiceConstants.FIELD_LAUNCH_CONFIG, primaryLaunchConfig);
        fields.put(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS, secondaryLaunchConfigs);

        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, fields);

        ServiceRecord service = new ServiceRecord();
        service.setData(data);
        return service;
    }

    private static final class RecordingAllocationHelper implements InvocationHandler {
        int mergeCalls;

        AllocationHelper proxy() {
            return AllocationHelper.class.cast(Proxy.newProxyInstance(AllocationHelper.class.getClassLoader(),
                    new Class<?>[] { AllocationHelper.class }, this));
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
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
