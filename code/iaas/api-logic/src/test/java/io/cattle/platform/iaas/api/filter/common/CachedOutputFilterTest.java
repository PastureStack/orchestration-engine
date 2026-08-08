package io.cattle.platform.iaas.api.filter.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class CachedOutputFilterTest {

    @Test
    public void reusesCachedValueFromRequestAttribute() {
        ApiRequest request = new ApiRequest(null, null);
        Map<Long, String> cached = new HashMap<Long, String>();
        cached.put(1L, "cached");
        MapCachedOutputFilter filter = new MapCachedOutputFilter();

        request.setAttribute(filter, cached);

        assertSame(cached, filter.getCached(request));
        assertEquals(0, filter.created);
    }

    @Test
    public void createsAndStoresMissingValue() {
        ApiRequest request = new ApiRequest(null, null);
        MapCachedOutputFilter filter = new MapCachedOutputFilter();

        Map<Long, String> created = filter.getCached(request);

        assertEquals("created", created.get(2L));
        assertSame(created, request.getAttribute(filter));
        assertEquals(1, filter.created);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWrongCachedMapBoundary() {
        ApiRequest request = new ApiRequest(null, null);
        MapCachedOutputFilter filter = new MapCachedOutputFilter();

        request.setAttribute(filter, "not-a-map");

        filter.getCached(request);
    }

    static class MapCachedOutputFilter extends CachedOutputFilter<Map<Long, String>> {
        int created;

        @Override
        protected Map<Long, String> newObject(ApiRequest apiRequest) {
            created++;
            Map<Long, String> result = new HashMap<Long, String>();
            result.put(2L, "created");
            return result;
        }

        @Override
        protected Map<Long, String> castCached(Object cached) {
            return io.cattle.platform.util.type.CollectionUtils.castMap(cached);
        }

        @Override
        protected Long getId(Object obj) {
            return null;
        }

        @Override
        public String[] getTypes() {
            return new String[0];
        }

        @Override
        public Class<?>[] getTypeClasses() {
            return new Class<?>[0];
        }

        @Override
        public Resource filter(ApiRequest request, Object original, Resource converted) {
            return converted;
        }
    }
}
