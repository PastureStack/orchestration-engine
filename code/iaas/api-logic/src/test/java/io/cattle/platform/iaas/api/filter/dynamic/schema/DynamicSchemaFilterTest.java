package io.cattle.platform.iaas.api.filter.dynamic.schema;

import static io.cattle.platform.object.meta.ObjectMetaDataManager.ACCOUNT_FIELD;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockCallbackWithException;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.provider.LockProvider;
import io.github.ibuildthecloud.gdapi.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class DynamicSchemaFilterTest {

    @Test
    public void createNormalizesRequestObjectBeforeDelegating() {
        DynamicSchemaFilter filter = new DynamicSchemaFilter();
        filter.lockManager = new ImmediateLockManager();
        filter.jsonMapper = new AcceptingJsonMapper();

        ApiRequest request = new ApiRequest(null, null);
        Map<Object, Object> body = new LinkedHashMap<Object, Object>();
        body.put("name", "customSchema");
        body.put("roles", Arrays.asList("admin"));
        body.put("definition", "{}");
        request.setRequestObject(body);

        CapturingResourceManager next = new CapturingResourceManager();
        Object result = filter.create("dynamicSchema", request, next);

        assertSame(next.created, result);
        assertSame(request, next.request);
        Object forwarded = request.getRequestObject();
        assertTrue(forwarded instanceof Map<?, ?>);
        assertTrue(((Map<?, ?>) forwarded).containsKey(ACCOUNT_FIELD));
    }

    @Test(expected = ClassCastException.class)
    public void requestObjectRejectsNonMapValues() {
        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject("invalid");

        DynamicSchemaFilter.requestObject(request);
    }

    @Test(expected = ClassCastException.class)
    public void requestObjectRejectsNonStringKeys() {
        ApiRequest request = new ApiRequest(null, null);
        Map<Object, Object> body = new LinkedHashMap<Object, Object>();
        body.put(Integer.valueOf(1), "invalid");
        request.setRequestObject(body);

        DynamicSchemaFilter.requestObject(request);
    }

    @Test(expected = ClassCastException.class)
    public void rolesRejectsNonListValues() {
        DynamicSchemaFilter.roles("admin");
    }

    private static final class ImmediateLockManager implements LockManager {

        @Override
        public <T, E extends Throwable> T lock(LockDefinition lockDef,
                LockCallbackWithException<T, E> callback, Class<E> clz) throws E {
            return callback.doWithLock();
        }

        @Override
        public <T> T lock(LockDefinition lockDef, LockCallback<T> callback) {
            return callback.doWithLock();
        }

        @Override
        public <T> T tryLock(LockDefinition lockDef, LockCallback<T> callback) {
            return callback.doWithLock();
        }

        @Override
        public <T, E extends Throwable> T tryLock(LockDefinition lockDef,
                LockCallbackWithException<T, E> callback, Class<E> clz) throws E {
            return callback.doWithLock();
        }

        @Override
        public LockProvider getLockProvider() {
            return null;
        }
    }

    private static final class AcceptingJsonMapper implements JsonMapper {

        @Override
        public <T> T readValue(byte[] content, Class<T> type) throws IOException {
            return type.cast(new SchemaImpl());
        }

        @Override
        public Object readValue(byte[] content) throws IOException {
            return new Object();
        }

        @Override
        public void writeValue(OutputStream os, Object object) throws IOException {
        }

        @Override
        public <T> T convertValue(Object fromValue, Class<T> toValueType) {
            return toValueType.cast(fromValue);
        }
    }

    private static final class CapturingResourceManager implements ResourceManager {
        final Object created = new Object();
        ApiRequest request;

        @Override
        public String[] getTypes() {
            return new String[0];
        }

        @Override
        public Class<?>[] getTypeClasses() {
            return new Class<?>[0];
        }

        @Override
        public Object getById(String type, String id, ListOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object getLink(String type, String id, String link, ApiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object list(String type, ApiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<?> list(String type, Map<Object, Object> criteria, ListOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object create(String type, ApiRequest request) {
            this.request = request;
            return created;
        }

        @Override
        public Object update(String type, String id, ApiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object delete(String type, String id, ApiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object resourceAction(String type, ApiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object collectionAction(String type, ApiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.github.ibuildthecloud.gdapi.model.Collection convertResponse(List<?> object, ApiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Resource convertResponse(Object obj, ApiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean handleException(Throwable t, ApiRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
