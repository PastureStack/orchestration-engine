package io.cattle.platform.api.resource.jooq;

import static io.cattle.platform.core.model.tables.ProjectTemplateTable.PROJECT_TEMPLATE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.DefaultPolicy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.jooq.Condition;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ProjectTemplateMutationAuthorizationTest {

    private final DefaultJooqResourceManager manager = new DefaultJooqResourceManager();
    private final Policy user = new DefaultPolicy(42L, 42L, "user42",
            Collections.<Identity>emptySet(), new NoPolicyOptions());
    private ApiRequest request;

    @Before
    public void setUp() {
        request = new ApiRequest(null, null);
        ApiContext.newContext().setApiRequest(request);
        manager.setMetaDataManager(ObjectMetaDataManager.class.cast(Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] { ObjectMetaDataManager.class },
                (proxy, method, args) -> {
                    if (!"convertFieldNameFor".equals(method.getName())) {
                        throw new UnsupportedOperationException(method.getName());
                    }
                    if (ObjectMetaDataManager.ACCOUNT_FIELD.equals(args[1])) {
                        return PROJECT_TEMPLATE.ACCOUNT_ID;
                    }
                    if (ObjectMetaDataManager.PUBLIC_FIELD.equals(args[1])) {
                        return PROJECT_TEMPLATE.IS_PUBLIC;
                    }
                    return null;
                })));
    }

    @After
    public void tearDown() {
        ApiContext.remove();
    }

    @Test
    public void publicReadMayUsePublicOrOwnerCondition() {
        request.setMethod("GET");
        Map<Object, Object> criteria = criteria(user);

        assertFalse(criteria.containsKey(ObjectMetaDataManager.ACCOUNT_FIELD));
        assertTrue(criteria.containsKey(Condition.class));
        assertTrue(criteria.get(Condition.class).toString().contains("is_public"));
    }

    @Test
    public void directPutDeleteAndRemoveActionKeepOwnerCriterion() {
        for (String method : new String[] { "PUT", "DELETE", "POST" }) {
            request.setMethod(method);
            request.setAction("POST".equals(method) ? "remove" : null);
            Map<Object, Object> criteria = criteria(user);

            assertEquals(method, 42L, criteria.get(ObjectMetaDataManager.ACCOUNT_FIELD));
            assertFalse(method, criteria.containsKey(Condition.class));
        }
    }

    @Test
    public void allAccountsAdminMayMutateAnyTemplate() {
        request.setMethod("DELETE");
        Policy admin = new DefaultPolicy(1L, 1L, "admin",
                Collections.<Identity>emptySet(), new NoPolicyOptions()) {
            @Override
            public boolean isOption(String optionName) {
                return Policy.LIST_ALL_ACCOUNTS.equals(optionName)
                        || Policy.AUTHORIZED_FOR_ALL_ACCOUNTS.equals(optionName);
            }
        };

        Map<Object, Object> criteria = criteria(admin);

        assertFalse(criteria.containsKey(ObjectMetaDataManager.ACCOUNT_FIELD));
        assertFalse(criteria.containsKey(Condition.class));
    }

    private Map<Object, Object> criteria(Policy policy) {
        Map<Object, Object> criteria = new HashMap<>();
        manager.addAccountAuthorization(true, false, "projectTemplate", criteria, policy);
        return criteria;
    }
}
