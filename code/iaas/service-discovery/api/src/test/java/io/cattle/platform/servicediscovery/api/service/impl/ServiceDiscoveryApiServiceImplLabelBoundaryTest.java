package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.Test;

public class ServiceDiscoveryApiServiceImplLabelBoundaryTest {

    @Test
    public void excludeRancherHashRemovesHashFromLabelsAndMetadata() throws Exception {
        Map<String, Object> composeServiceData = new HashMap<>();
        Map<Object, Object> labels = new HashMap<>();
        labels.put(ServiceConstants.LABEL_SERVICE_HASH, "hash-1");
        labels.put("custom", Integer.valueOf(42));
        Map<Object, Object> metadata = new HashMap<>();
        metadata.put(ServiceConstants.LABEL_SERVICE_HASH, "hash-2");
        composeServiceData.put(InstanceConstants.FIELD_LABELS, labels);
        composeServiceData.put(InstanceConstants.FIELD_METADATA, metadata);

        invokeExcludeRancherHash(composeServiceData);

        Map<?, ?> updatedLabels = Map.class.cast(composeServiceData.get(InstanceConstants.FIELD_LABELS));
        Map<?, ?> updatedMetadata = Map.class.cast(composeServiceData.get(InstanceConstants.FIELD_METADATA));
        assertFalse(updatedLabels.containsKey(ServiceConstants.LABEL_SERVICE_HASH));
        assertFalse(updatedMetadata.containsKey(ServiceConstants.LABEL_SERVICE_HASH));
        assertEquals(Integer.valueOf(42), updatedLabels.get("custom"));
    }

    @Test(expected = ClassCastException.class)
    public void excludeRancherHashRejectsNonHashMapLabels() throws Exception {
        Map<String, Object> composeServiceData = new HashMap<>();
        Map<Object, Object> labels = new TreeMap<>();
        labels.put(ServiceConstants.LABEL_SERVICE_HASH, "hash-1");
        composeServiceData.put(InstanceConstants.FIELD_LABELS, labels);

        invokeExcludeRancherHash(composeServiceData);
    }

    @Test
    public void formatScaleRemovesScaleForGlobalService() {
        Map<String, Object> composeServiceData = dataWithGlobalLabel("true");

        new ServiceDiscoveryApiServiceImpl().formatScale(null, composeServiceData);

        assertFalse(composeServiceData.containsKey(ServiceConstants.FIELD_SCALE));
    }

    @Test
    public void formatScaleKeepsScaleForNonGlobalService() {
        Map<String, Object> composeServiceData = dataWithGlobalLabel("false");

        new ServiceDiscoveryApiServiceImpl().formatScale(null, composeServiceData);

        assertEquals(Integer.valueOf(2), composeServiceData.get(ServiceConstants.FIELD_SCALE));
    }

    @Test(expected = ClassCastException.class)
    public void formatScaleRejectsNonStringGlobalLabel() {
        Map<String, Object> composeServiceData = dataWithGlobalLabel(Boolean.TRUE);

        new ServiceDiscoveryApiServiceImpl().formatScale(null, composeServiceData);
    }

    private static Map<String, Object> dataWithGlobalLabel(Object globalValue) {
        Map<String, Object> composeServiceData = new HashMap<>();
        Map<Object, Object> labels = new HashMap<>();
        labels.put(ServiceConstants.LABEL_SERVICE_GLOBAL, globalValue);
        composeServiceData.put(InstanceConstants.FIELD_LABELS, labels);
        composeServiceData.put(ServiceConstants.FIELD_SCALE, Integer.valueOf(2));
        return composeServiceData;
    }

    private static void invokeExcludeRancherHash(Map<String, Object> composeServiceData) throws Exception {
        Method method = ServiceDiscoveryApiServiceImpl.class.getDeclaredMethod("excludeRancherHash", Map.class);
        method.setAccessible(true);
        try {
            method.invoke(new ServiceDiscoveryApiServiceImpl(), composeServiceData);
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
}
