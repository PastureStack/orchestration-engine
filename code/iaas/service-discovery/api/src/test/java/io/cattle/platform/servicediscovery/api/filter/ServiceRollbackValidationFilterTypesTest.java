package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.cattle.platform.core.constants.ServiceConstants;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class ServiceRollbackValidationFilterTypesTest {

    @Test
    public void rollbackRestoresConfigsForEveryUpgradeableServiceType() {
        String[] actualTypes = new ServiceRollbackValidationFilter().getTypes();
        Set<String> actual = new HashSet<>(Arrays.asList(actualTypes));
        Set<String> upgradeTypes = new HashSet<>(Arrays.asList(
                new ServiceUpgradeValidationFilter().getTypes()));

        assertEquals(upgradeTypes, actual);
        assertEquals(actual.size(), actualTypes.length);
        assertTrue(actual.contains(ServiceConstants.KIND_NETWORK_DRIVER_SERVICE));
        assertTrue(actual.contains(ServiceConstants.KIND_STORAGE_DRIVER_SERVICE));
    }
    @Test
    public void everyNativeServiceRollbackRestoresOnlyItsOwnConfigurationsBeforeForwarding() {
        for (String type : new ServiceRollbackValidationFilter().getTypes()) {
            ServiceRecord service = new ServiceRecord();
            service.setKind(type);
            Map<String, Object> old = Map.of("imageUuid", "docker:old");
            List<Map<String, Object>> secondary = List.of(Map.of("name", "sidecar", "imageUuid", "docker:sidecar-old"));
            DataAccessor.fields(service).withKey("launchConfig").set(Map.of("imageUuid", "docker:new"));
            DataAccessor.fields(service).withKey("secondaryLaunchConfigs").set(List.of());
            DataAccessor.fields(service).withKey("upgrade").set(Map.of("inServiceStrategy", Map.of(
                    "previousLaunchConfig", old, "previousSecondaryLaunchConfigs", secondary)));
            List<Service> writes = new ArrayList<>();
            int[] reads = { 0 };
            ServiceRollbackValidationFilter filter = new ServiceRollbackValidationFilter();
            filter.jsonMapper = new JacksonJsonMapper();
            filter.objectManager = (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                    new Class<?>[] { ObjectManager.class }, (proxy, method, arguments) -> {
                        if ("loadResource".equals(method.getName())) {
                            assertEquals(Service.class, arguments[0]);
                            assertEquals("1s7", arguments[1]);
                            reads[0]++;
                            return service;
                        }
                        if ("persist".equals(method.getName())) {
                            assertSame(service, arguments[0]);
                            writes.add(service);
                            return service;
                        }
                        throw new AssertionError(method.getName());
                    });
            ApiRequest request = new ApiRequest(null, null);
            request.setId("1s7");
            request.setAction("rollback");
            Object expected = new Object();
            ResourceManager next = (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                    new Class<?>[] { ResourceManager.class }, (proxy, method, arguments) -> {
                        if (!"resourceAction".equals(method.getName())) return null;
                        assertEquals(type, arguments[0]);
                        assertSame(request, arguments[1]);
                        assertEquals(old, DataAccessor.fieldMap(service, "launchConfig"));
                        assertEquals(secondary, DataAccessor.field(service, "secondaryLaunchConfigs", Object.class));
                        assertEquals(1, writes.size());
                        return expected;
                    });
            assertSame(type, expected, filter.resourceAction(type, request, next));
            assertEquals(1, reads[0]);
            // Other actions and absent previous configuration are forwarded with no new writes.
            writes.clear();
            request.setAction("finishupgrade");
            ResourceManager pass = (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                    new Class<?>[] { ResourceManager.class }, (proxy, method, arguments) -> expected);
            assertSame(expected, filter.resourceAction(type, request, pass));
            assertEquals(1, reads[0]);
            request.setAction("rollback");
            DataAccessor.fields(service).withKey("upgrade").set(Map.of("inServiceStrategy", Map.of()));
            assertSame(expected, filter.resourceAction(type, request, pass));
            assertTrue(writes.isEmpty());
            assertEquals(old, DataAccessor.fieldMap(service, "launchConfig"));
            IllegalStateException original = new IllegalStateException("native state conflict");
            ResourceManager fail = (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                    new Class<?>[] { ResourceManager.class }, (proxy, method, arguments) -> { throw original; });
            assertSame(original, assertThrows(IllegalStateException.class, () -> filter.resourceAction(type, request, fail)));
            assertTrue(writes.isEmpty());
        }
    }

}
