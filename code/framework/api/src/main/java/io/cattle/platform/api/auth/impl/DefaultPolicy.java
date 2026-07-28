package io.cattle.platform.api.auth.impl;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DefaultPolicy implements Policy {

    private static final String WHITELIST_ATTRIBUTE = "whitelist";

    long accountId;
    long authenticatedAsAccountId;
    String name;
    Set<Identity> identities;
    PolicyOptions options;

    public DefaultPolicy() {
        this(Policy.NO_ACCOUNT, Policy.NO_ACCOUNT, null, Collections.<Identity>emptySet(), new NoPolicyOptions());
    }

    public DefaultPolicy(long accountId, long authenticatedAsAccountId, String name, Set<Identity> identities, PolicyOptions options) {
        super();
        this.accountId = accountId;
        this.authenticatedAsAccountId = authenticatedAsAccountId;
        this.identities = identities;
        this.options = options;
        this.name = name;
    }

    @Override
    public Set<Identity> getIdentities(){
        return identities;
    }

    @Override
    public boolean isOption(String optionName) {
        return options.isOption(optionName);
    }

    @Override
    public String getOption(String optionName) {
        return options.getOption(optionName);
    }

    @Override
    public <T> List<T> authorizeList(List<T> list) {
        List<T> result = new ArrayList<T>(list.size());
        for (T obj : list) {
            T authorized = authorizeObject(obj);
            if (authorized != null)
                result.add(authorized);
        }
        return result;
    }

    @Override
    public <T> T authorizeObject(T obj) {
        return obj;
    }

    @Override
    public long getAccountId() {
        return accountId;
    }

    @Override
    public long getAuthenticatedAsAccountId() {
        return authenticatedAsAccountId;
    }

    @Override
    public String getUserName() {
        return name;
    }

    @Override
    public <T> void grantObjectAccess(T obj) {
        ApiRequest apiRequest = ApiContext.getContext().getApiRequest();
        Set<Object> whitelist = whitelist(apiRequest);
        if (whitelist == null) {
            whitelist = new HashSet<Object>();
        }
        whitelist.add(obj);
        apiRequest.setAttribute(WHITELIST_ATTRIBUTE, whitelist);
    }

    protected <T> boolean hasGrantedAccess(T obj) {
        ApiRequest request = ApiContext.getContext().getApiRequest();
        Set<Object> whitelist = whitelist(request);
        return (null != whitelist && whitelist.contains(obj));
    }

    private Set<Object> whitelist(ApiRequest request) {
        Object value = request.getAttribute(WHITELIST_ATTRIBUTE);
        if (value == null) {
            return null;
        }
        if (!(value instanceof Set<?>)) {
            throw new ClassCastException(value.getClass().getName() + " cannot be cast to java.util.Set");
        }

        Set<Object> result = new HashSet<Object>();
        result.addAll((Set<?>) value);
        return result;
    }

    @Override
    public Set<String> getRoles() {
        return Collections.emptySet();
    }

}
