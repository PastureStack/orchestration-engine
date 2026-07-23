package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Image;
import io.cattle.platform.core.model.StorageDriver;
import io.cattle.platform.storage.service.StorageService;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ServiceValidationFilterImageRewriteTest {

    @Test
    public void validateAndSetImageRewritesPrimaryImageInPlace() {
        ServiceValidationFilter filter = filter();
        Map<String, Object> launchConfig = launchConfig("docker:ubuntu:24.04");
        ApiRequest request = requestWith(ServiceConstants.FIELD_LAUNCH_CONFIG, launchConfig);

        ApiRequest result = filter.validateAndSetImage(request, null, ServiceConstants.KIND_SERVICE);

        assertSame(request, result);
        assertEquals("docker:ubuntu:24.04", launchConfig.get(InstanceConstants.FIELD_IMAGE_UUID));
        assertSame(launchConfig, requestData(request).get(ServiceConstants.FIELD_LAUNCH_CONFIG));
    }

    @Test
    public void validateAndSetImageSkipsImageNone() {
        ServiceValidationFilter filter = filter();
        Map<String, Object> launchConfig = launchConfig(ServiceConstants.IMAGE_NONE);
        ApiRequest request = requestWith(ServiceConstants.FIELD_LAUNCH_CONFIG, launchConfig);

        filter.validateAndSetImage(request, null, ServiceConstants.KIND_SERVICE);

        assertEquals(ServiceConstants.IMAGE_NONE, launchConfig.get(InstanceConstants.FIELD_IMAGE_UUID));
    }

    @Test
    public void validateAndSetImageRewritesSecondaryImagesAndKeepsListOrder() {
        ServiceValidationFilter filter = filter();
        Map<String, Object> firstSidekick = launchConfig("docker:redis:7");
        Map<String, Object> secondSidekick = launchConfig("docker:mysql:8");
        List<Object> sidekicks = new ArrayList<>();
        sidekicks.add(firstSidekick);
        sidekicks.add(secondSidekick);
        ApiRequest request = requestWith(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS, sidekicks);

        filter.validateAndSetImage(request, null, ServiceConstants.KIND_SERVICE);

        List<?> rewrittenSidekicks = List.class.cast(requestData(request)
                .get(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS));
        assertSame(firstSidekick, rewrittenSidekicks.get(0));
        assertSame(secondSidekick, rewrittenSidekicks.get(1));
        assertEquals("docker:redis:7", firstSidekick.get(InstanceConstants.FIELD_IMAGE_UUID));
        assertEquals("docker:mysql:8", secondSidekick.get(InstanceConstants.FIELD_IMAGE_UUID));
    }

    @Test(expected = ClassCastException.class)
    public void validateAndSetImageRejectsNonMapPrimaryLaunchConfig() {
        ServiceValidationFilter filter = filter();
        ApiRequest request = requestWith(ServiceConstants.FIELD_LAUNCH_CONFIG, "not-a-map");

        filter.validateAndSetImage(request, null, ServiceConstants.KIND_SERVICE);
    }

    @Test(expected = ClassCastException.class)
    public void validateAndSetImageRejectsNonListSecondaryLaunchConfigs() {
        ServiceValidationFilter filter = filter();
        ApiRequest request = requestWith(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS, "not-a-list");

        filter.validateAndSetImage(request, null, ServiceConstants.KIND_SERVICE);
    }

    @Test(expected = ClassCastException.class)
    public void validateAndSetImageRejectsNonMapSecondaryLaunchConfig() {
        ServiceValidationFilter filter = filter();
        List<Object> sidekicks = new ArrayList<>();
        sidekicks.add("not-a-map");
        ApiRequest request = requestWith(ServiceConstants.FIELD_SECONDARY_LAUNCH_CONFIGS, sidekicks);

        filter.validateAndSetImage(request, null, ServiceConstants.KIND_SERVICE);
    }

    private static ServiceValidationFilter filter() {
        ServiceValidationFilter filter = new ServiceValidationFilter();
        filter.storageService = new AcceptAllStorageService();
        return filter;
    }

    private static ApiRequest requestWith(String field, Object value) {
        Map<String, Object> data = new HashMap<>();
        data.put(field, value);
        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(data);
        return request;
    }

    private static Map<String, Object> launchConfig(String imageUuid) {
        Map<String, Object> launchConfig = new HashMap<>();
        launchConfig.put(InstanceConstants.FIELD_IMAGE_UUID, imageUuid);
        return launchConfig;
    }

    private static Map<?, ?> requestData(ApiRequest request) {
        return Map.class.cast(request.getRequestObject());
    }

    private static class AcceptAllStorageService implements StorageService {
        @Override
        public Image registerRemoteImage(String uuid) {
            return null;
        }

        @Override
        public boolean isValidUUID(String uuid) {
            return true;
        }

        @Override
        public void setupPools(StorageDriver storageDriver) {
        }
    }
}
