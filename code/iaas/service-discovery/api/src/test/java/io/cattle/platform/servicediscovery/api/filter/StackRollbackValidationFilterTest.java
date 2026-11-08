package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;

import java.lang.reflect.Proxy;

import org.junit.Test;

public class StackRollbackValidationFilterTest {

    @Test
    public void stackRollbackDelegatesWithoutReadingOrPrewritingAnyChildConfiguration() {
        StackValidationFilter filter = new StackValidationFilter();
        filter.objMgr = (ObjectManager) Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                new Class<?>[] { ObjectManager.class }, (proxy, method, arguments) -> {
                    throw new AssertionError("Stack action must not read or rewrite Service configurations: " + method.getName());
                });
        ApiRequest request = new ApiRequest(null, null);
        request.setId("1st42");
        request.setAction(ServiceConstants.ACTION_SERVICE_ROLLBACK);
        Object expected = new Object();
        ResourceManager next = next((type, actual) -> {
            assertEquals("stack", type);
            assertSame(request, actual);
            return expected;
        });
        assertSame(expected, filter.resourceAction("stack", request, next));
        IllegalStateException failure = new IllegalStateException("native action unavailable");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> filter.resourceAction("stack", request, next((type, actual) -> { throw failure; }))));
    }

    private static ResourceManager next(java.util.function.BiFunction<String, ApiRequest, Object> action) {
        return (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                new Class<?>[] { ResourceManager.class }, (proxy, method, arguments) ->
                    "resourceAction".equals(method.getName()) ? action.apply((String) arguments[0], (ApiRequest) arguments[1]) : null);
    }
}
