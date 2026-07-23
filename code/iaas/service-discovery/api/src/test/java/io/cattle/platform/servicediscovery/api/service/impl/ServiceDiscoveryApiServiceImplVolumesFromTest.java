package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.docker.constants.DockerInstanceConstants;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.servicediscovery.api.resource.ServiceDiscoveryConfigItem;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryApiServiceImplVolumesFromTest {

    @Test
    public void populateVolumesFromMergesLaunchConfigAndInstanceNames() throws Exception {
        Map<String, Object> fields = new HashMap<>();
        fields.put(ServiceConstants.FIELD_DATA_VOLUMES_LAUNCH_CONFIG, Arrays.asList("cache", "sidekick"));
        fields.put(DockerInstanceConstants.FIELD_VOLUMES_FROM, Arrays.asList(Integer.valueOf(7), Integer.valueOf(8)));
        Map<String, Object> composeServiceData = new HashMap<>();

        invokePopulateVolumesForService(serviceWithPrimaryLaunchConfig(fields), composeServiceData, objectManager());

        assertEquals(Arrays.asList("cache", "sidekick", "instance-7", "instance-8"),
                composeServiceData.get(ServiceDiscoveryConfigItem.VOLUMESFROM.getDockerName()));
    }

    @Test
    public void populateVolumesFromPreservesLaunchConfigElementBoundary() throws Exception {
        Map<String, Object> fields = new HashMap<>();
        fields.put(ServiceConstants.FIELD_DATA_VOLUMES_LAUNCH_CONFIG, Arrays.asList("cache", Integer.valueOf(3)));
        Map<String, Object> composeServiceData = new HashMap<>();

        invokePopulateVolumesForService(serviceWithPrimaryLaunchConfig(fields), composeServiceData, objectManager());

        List<?> volumesFrom = List.class.cast(
                composeServiceData.get(ServiceDiscoveryConfigItem.VOLUMESFROM.getDockerName()));
        assertEquals("cache", volumesFrom.get(0));
        assertEquals(Integer.valueOf(3), volumesFrom.get(1));
    }

    @Test(expected = ClassCastException.class)
    public void populateVolumesFromRejectsNonIntegerInstanceIds() throws Exception {
        Map<String, Object> fields = new HashMap<>();
        fields.put(DockerInstanceConstants.FIELD_VOLUMES_FROM, Arrays.asList("not-an-id"));

        invokePopulateVolumesForService(serviceWithPrimaryLaunchConfig(fields), new HashMap<String, Object>(),
                objectManager());
    }

    private static void invokePopulateVolumesForService(Service service, Map<String, Object> composeServiceData,
            ObjectManager objectManager) throws Exception {
        ServiceDiscoveryApiServiceImpl api = new ServiceDiscoveryApiServiceImpl();
        api.objectManager = objectManager;
        Method method = ServiceDiscoveryApiServiceImpl.class.getDeclaredMethod("populateVolumesForService",
                Service.class, String.class, Map.class);
        method.setAccessible(true);
        try {
            method.invoke(api, service, ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME, composeServiceData);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw e;
        }
    }

    private static Service serviceWithPrimaryLaunchConfig(Map<String, Object> launchConfig) {
        Map<String, Object> fields = new HashMap<>();
        fields.put(ServiceConstants.FIELD_LAUNCH_CONFIG, launchConfig);
        Map<String, Object> data = new HashMap<>();
        data.put(DataUtils.FIELDS, fields);
        ServiceRecord service = new ServiceRecord();
        service.setData(data);
        return service;
    }

    private static ObjectManager objectManager() {
        Map<Integer, Instance> instances = new HashMap<>();
        instances.put(Integer.valueOf(7), instance("instance-7"));
        instances.put(Integer.valueOf(8), instance("instance-8"));
        return ObjectManager.class.cast(Proxy.newProxyInstance(
                ObjectManager.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class },
                (proxy, method, args) -> {
                    if ("findOne".equals(method.getName()) && args.length >= 2 && args[0] == Instance.class) {
                        return instances.get(findIntegerArg(args));
                    }
                    throw new UnsupportedOperationException(method.getName());
                }));
    }

    private static Integer findIntegerArg(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof Integer) {
                return Integer.class.cast(arg);
            }
            if (arg instanceof Object[]) {
                Integer value = findIntegerArg(Object[].class.cast(arg));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    private static Instance instance(String uuid) {
        InstanceRecord instance = new InstanceRecord();
        instance.setUuid(uuid);
        return instance;
    }
}
