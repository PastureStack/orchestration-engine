package io.cattle.platform.servicediscovery.api.service.impl;

import static org.junit.Assert.assertEquals;

import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.servicediscovery.api.resource.ServiceDiscoveryConfigItem;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class ServiceDiscoveryApiServiceImplSystemImageTest {

    @Test
    public void composeExportUsesPastureStackSystemImageSentinels() throws Exception {
        assertEquals("ghcr.io/pasturestack/internal-dns",
                exportedImage(ServiceConstants.KIND_DNS_SERVICE));
        assertEquals("ghcr.io/pasturestack/load-balancer-service",
                exportedImage(ServiceConstants.KIND_LOAD_BALANCER_SERVICE));
        assertEquals("ghcr.io/pasturestack/external-service",
                exportedImage(ServiceConstants.KIND_EXTERNAL_SERVICE));
    }

    private static String exportedImage(String kind) throws Exception {
        ServiceRecord service = new ServiceRecord();
        service.setKind(kind);
        Map<String, Object> composeServiceData = new HashMap<>();
        ServiceDiscoveryApiServiceImpl api = new ServiceDiscoveryApiServiceImpl();
        api.jsonMapper = new JacksonJsonMapper();

        Method method = ServiceDiscoveryApiServiceImpl.class.getDeclaredMethod("addExtraComposeParameters",
                Service.class, String.class, Map.class);
        method.setAccessible(true);
        method.invoke(api, service, ServiceConstants.PRIMARY_LAUNCH_CONFIG_NAME, composeServiceData);

        return String.class.cast(composeServiceData.get(ServiceDiscoveryConfigItem.IMAGE.getDockerName()));
    }
}
