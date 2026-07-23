package io.cattle.platform.api.auth.impl;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.HashSet;
import java.util.Set;

import org.junit.After;
import org.junit.Test;

public class DefaultPolicyTest {

    private static final String WHITELIST_ATTRIBUTE = "whitelist";

    @After
    public void clearApiContext() {
        ApiContext.remove();
    }

    @Test
    public void defaultIdentitiesRemainEmpty() {
        DefaultPolicy policy = new DefaultPolicy();

        assertTrue(policy.getIdentities().isEmpty());
    }

    @Test
    public void grantObjectAccessCreatesWhitelistForCurrentRequest() {
        TestPolicy policy = policyWithRequest();
        Object granted = new Object();
        Object denied = new Object();

        policy.grantObjectAccess(granted);

        assertTrue(policy.hasAccess(granted));
        assertFalse(policy.hasAccess(denied));
    }

    @Test
    public void grantObjectAccessPreservesExistingWhitelistValues() {
        TestPolicy policy = policyWithRequest();
        ApiRequest request = ApiContext.getContext().getApiRequest();
        Set<Object> existing = new HashSet<Object>();
        existing.add("existing");
        request.setAttribute(WHITELIST_ATTRIBUTE, existing);

        Object granted = new Object();
        policy.grantObjectAccess(granted);

        assertTrue(policy.hasAccess("existing"));
        assertTrue(policy.hasAccess(granted));
    }

    @Test
    public void rejectsInvalidWhitelistAttributeType() {
        TestPolicy policy = policyWithRequest();
        ApiContext.getContext().getApiRequest().setAttribute(WHITELIST_ATTRIBUTE, "not-a-set");

        try {
            policy.hasAccess("existing");
            fail("Expected ClassCastException");
        } catch (ClassCastException expected) {
            assertTrue(expected.getMessage().contains("java.util.Set"));
        }
    }

    private TestPolicy policyWithRequest() {
        ApiContext context = ApiContext.newContext();
        context.setApiRequest(new ApiRequest(null, null));
        return new TestPolicy();
    }

    private static class TestPolicy extends DefaultPolicy {
        boolean hasAccess(Object obj) {
            return hasGrantedAccess(obj);
        }
    }
}
