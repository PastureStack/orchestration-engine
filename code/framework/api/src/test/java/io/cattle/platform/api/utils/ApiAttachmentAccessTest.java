package io.cattle.platform.api.utils;

import static org.junit.Assert.*;

import io.cattle.platform.api.auth.ApiResourceAccess;
import io.cattle.platform.api.auth.Policy;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.Condition;
import org.jooq.Table;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiAttachmentAccessTest {
    private final Bean root = new Bean(1L);
    private ApiRequest request;
    private SchemaImpl schema;
    private ResourceManager manager;
    private final List<Long> converted = new ArrayList<>();

    @Before
    public void setup() throws Exception {
        request = new ApiRequest(null, null);
        request.setMethod("GET");
        ApiContext.newContext().setApiRequest(request);
        ApiContext.getContext().setPolicy((Policy) Proxy.newProxyInstance(Policy.class.getClassLoader(), new Class<?>[]{Policy.class},
                (proxy, method, args) -> method.getName().equals("authorizeObject")
                        ? Long.valueOf(3L).equals(((Bean) args[0]).getId()) ? null : args[0] : null));
        schema = new SchemaImpl();
        schema.setId("root");
        FieldImpl id = new FieldImpl();
        id.setType("string");
        id.setReadMethod(Bean.class.getMethod("getId"));
        schema.getResourceFields().put("id", id);
        manager = (ResourceManager) Proxy.newProxyInstance(ResourceManager.class.getClassLoader(), new Class<?>[]{ResourceManager.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("convertResponse")) {
                        Bean value = (Bean) args[0];
                        converted.add(value.getId());
                        return new ResourceImpl(value.getId().toString(), "child", Map.of());
                    }
                    return null;
                });
        Object key = ApiUtils.getAttachementKey(root);
        for (long child : new long[]{2, 3, 4}) ApiUtils.addAttachement(key, "children", new Bean(child));
        ApiUtils.addAttachement(key, ApiUtils.SINGLE_ATTACHMENT_PREFIX + "primary", new Bean(4L));
    }

    @After public void cleanup() { ApiContext.remove(); }

    @Test
    public void ownerThenSameKeyGuardRunBeforeIncludedObjectsAreConverted() {
        List<Long> checked = new ArrayList<>();
        ApiResourceAccess access = guard((values) -> {
            checked.addAll(values.stream().map(value -> ((Bean) value).getId()).toList());
            return values.stream().filter(value -> ((Bean) value).getId() == 2L).toList();
        });
        Resource rendered = render(List.of(access));
        assertEquals(List.of(2L), includedIds(rendered));
        assertNull(rendered.getFields().get("primary"));
        assertFalse(checked.contains(3L)); // Owner denied it before Key evaluation.
        assertTrue(checked.contains(4L)); // Key denied both list and single includes.
        assertEquals(List.of(2L), converted);
    }

    @Test
    public void originalSignatureAndPassThroughGuardPreserveNonKeyAndFullResults() {
        Resource original = ApiUtils.createResourceWithAttachments(manager, request, ApiContext.getContext().getIdFormatter(),
                new SchemaFactoryImpl(), schema, root, Map.of());
        Resource full = render(List.of(guard(values -> values)));
        assertEquals(List.of(2L, 4L), includedIds(original));
        assertEquals(includedIds(original), includedIds(full));
        assertEquals("4", ((Resource) full.getFields().get("primary")).getId());
    }

    @Test
    public void includedObjectsRecheckLiveAccessRatherThanCachingRenderedAuthority() {
        AtomicBoolean allowed = new AtomicBoolean(true);
        ApiResourceAccess live = guard(values -> allowed.get() ? values : List.of());
        assertEquals(List.of(2L, 4L), includedIds(render(List.of(live))));
        allowed.set(false);
        Resource revoked = render(List.of(live));
        assertTrue(includedIds(revoked).isEmpty());
        assertNull(revoked.getFields().get("primary"));
    }

    private Resource render(List<ApiResourceAccess> access) {
        return ApiUtils.createResourceWithAttachments(manager, request, ApiContext.getContext().getIdFormatter(),
                new SchemaFactoryImpl(), schema, root, Map.of(), access);
    }

    private List<Long> includedIds(Resource resource) {
        return ((List<?>) resource.getFields().get("children")).stream().map(value -> Long.valueOf(((Resource) value).getId())).toList();
    }

    private static ApiResourceAccess guard(java.util.function.Function<List<?>, List<?>> filter) {
        return new ApiResourceAccess() {
            @Override public Condition constrain(ApiRequest request, SchemaFactory factory, String type, Table<?> table) { return null; }
            @Override public List<?> filterCollection(ApiRequest request, List<?> values) { return filter.apply(values); }
        };
    }

    public static final class Bean {
        private final Long id;
        Bean(Long id) { this.id = id; }
        public Long getId() { return id; }
        public Map<String, Object> getData() { return Map.of(); }
    }
}
