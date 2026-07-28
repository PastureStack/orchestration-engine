package io.cattle.platform.api.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiUtilsTest {

    @Before
    public void createApiContext() {
        ApiContext context = ApiContext.newContext();
        context.setApiRequest(new ApiRequest(null, null));
    }

    @After
    public void clearApiContext() {
        ApiContext.remove();
    }

    @Test
    public void addsAndReadsListAttachments() {
        TestObject root = new TestObject(1L);
        TestObject first = new TestObject(2L);
        TestObject second = new TestObject(3L);
        Object key = ApiUtils.getAttachementKey(root);

        ApiUtils.addAttachement(key, "children", first);
        ApiUtils.addAttachement(key, "children", second);

        Map<String, Object> attachments = ApiUtils.getAttachements(root, identity());

        assertEquals(Arrays.asList(first, second), attachments.get("children"));
    }

    @Test
    public void singleAttachmentPrefixReturnsSingleObject() {
        TestObject root = new TestObject(1L);
        TestObject primary = new TestObject(2L);

        ApiUtils.addAttachement(ApiUtils.getAttachementKey(root), ApiUtils.SINGLE_ATTACHMENT_PREFIX + "primary", primary);

        Map<String, Object> attachments = ApiUtils.getAttachements(root, identity());

        assertSame(primary, attachments.get("primary"));
    }

    @Test
    public void normalizesExistingRequestAttributeAttachmentMap() {
        TestObject root = new TestObject(1L);
        TestObject existing = new TestObject(2L);
        TestObject added = new TestObject(3L);
        Object key = ApiUtils.getAttachementKey(root);
        Map<Object, Object> legacyValues = new LinkedHashMap<Object, Object>();
        legacyValues.put(existing.getId(), existing);
        Map<Object, Object> legacyAttachments = new LinkedHashMap<Object, Object>();
        legacyAttachments.put("children", legacyValues);
        ApiContext.getContext().getApiRequest().setAttribute(key, legacyAttachments);

        ApiUtils.addAttachement(key, "children", added);
        Map<String, Object> attachments = ApiUtils.getAttachements(root, identity());

        assertEquals(Arrays.asList(existing, added), attachments.get("children"));
    }

    @Test(expected = ClassCastException.class)
    public void rejectsExistingRequestAttributeAttachmentMapWithNonStringKey() {
        TestObject root = new TestObject(1L);
        Map<Object, Object> legacyAttachments = new LinkedHashMap<Object, Object>();
        legacyAttachments.put(1L, new LinkedHashMap<Object, Object>());
        ApiContext.getContext().getApiRequest().setAttribute(ApiUtils.getAttachementKey(root), legacyAttachments);

        ApiUtils.getAttachements(root, identity());
    }

    @Test
    public void authorizeKeepsTypedObjectAndTypedListPolicies() {
        ApiContext.getContext().setPolicy(new FilteringPolicy());
        TestObject allowed = new TestObject(2L);
        TestObject blocked = new TestObject(3L);

        assertSame(allowed, ApiUtils.authorize(allowed));
        assertNull(ApiUtils.authorize(blocked));
        assertEquals(Arrays.asList(allowed), ApiUtils.authorize(Arrays.asList(allowed, blocked)));
    }

    @Test
    public void authorizeObjectOrListKeepsObjectTypedListPolicy() {
        ApiContext.getContext().setPolicy(new FilteringPolicy());
        TestObject allowed = new TestObject(2L);
        TestObject blocked = new TestObject(3L);
        Object input = Arrays.asList(allowed, blocked);

        Object result = ApiUtils.authorizeObjectOrList(input);

        assertEquals(Arrays.asList(allowed), result);
    }

    private Function<Object, Object> identity() {
        return new Function<Object, Object>() {
            @Override
            public Object apply(Object input) {
                return input;
            }
        };
    }

    public static class TestObject {
        private final Long id;

        TestObject(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }
    }

    private static class FilteringPolicy implements Policy {

        @Override
        public boolean isOption(String optionName) {
            return false;
        }

        @Override
        public String getOption(String optionName) {
            return null;
        }

        @Override
        public Set<Identity> getIdentities() {
            return Collections.emptySet();
        }

        @Override
        public long getAccountId() {
            return Policy.NO_ACCOUNT;
        }

        @Override
        public long getAuthenticatedAsAccountId() {
            return Policy.NO_ACCOUNT;
        }

        @Override
        public String getUserName() {
            return "test";
        }

        @Override
        public <T> List<T> authorizeList(List<T> list) {
            List<T> result = new java.util.ArrayList<T>();
            for (T item : list) {
                T authorized = authorizeObject(item);
                if (authorized != null) {
                    result.add(authorized);
                }
            }
            return result;
        }

        @Override
        public <T> T authorizeObject(T obj) {
            if (obj instanceof TestObject && Long.valueOf(3L).equals(((TestObject) obj).getId())) {
                return null;
            }
            return obj;
        }

        @Override
        public <T> void grantObjectAccess(T obj) {
        }

        @Override
        public Set<String> getRoles() {
            return Collections.emptySet();
        }
    }
}
