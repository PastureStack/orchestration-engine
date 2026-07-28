package io.cattle.platform.iaas.api.auth.impl;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.pubsub.util.SubscriptionUtils;
import io.cattle.platform.api.pubsub.util.SubscriptionUtils.SubscriptionStyle;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.AchaiusPolicyOptionsFactory;
import io.cattle.platform.iaas.api.auth.AuthorizationProvider;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.event.IaasEvents;
import io.cattle.platform.util.type.InitializationTask;
import io.cattle.platform.util.type.Priority;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SubSchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultAuthorizationProvider implements AuthorizationProvider, InitializationTask, Priority {

    public static final String ACCOUNT_SCHEMA_FACTORY_NAME = "accountSchemaFactoryName";

    private static final Logger log = LoggerFactory.getLogger(DefaultAuthorizationProvider.class);

    Map<String, SchemaFactory> schemaFactories = new HashMap<String, SchemaFactory>();
    List<SchemaFactory> schemaFactoryList;
    int priority = Priority.DEFAULT;
    AchaiusPolicyOptionsFactory optionsFactory;

    @Inject
    AuthDao authDao;

    @Inject
    AccountDao accountDao;

    @Override
    public SchemaFactory getSchemaFactory(Account account, Policy policy, ApiRequest request) {
        Object name = request.getAttribute(ACCOUNT_SCHEMA_FACTORY_NAME);

        if (name == null) {
            name = getRole(policy, request);
        }

        if (name != null) {
            SchemaFactory schemaFactory = getByName(request, name.toString());
            if (schemaFactory == null) {
                log.error("Failed to find schema factory [{}]", name);
            } else {
                return schemaFactory;
            }
        }
        Account authenticatedAsAccount = accountDao.getAccountById(policy.getAuthenticatedAsAccountId());
        Policy authenticatedAsPolicy = getPolicy(authenticatedAsAccount, authenticatedAsAccount, policy.getIdentities(), request);
        return getByName(request, authDao.getRole(account, policy, authenticatedAsPolicy));
    }

    protected SchemaFactory getByName(ApiRequest request, String name) {
        String version = request.getSchemaVersion();
        if (version == null) {
            return schemaFactories.get(name);
        }

        SchemaFactory factory = schemaFactories.get(version + "-" + name);
        if (factory != null) {
            return factory;
        }

        return schemaFactories.get(name);
    }

    @Override
    public String getRole(Account account, Policy policy, ApiRequest request) {
        Object name = request.getAttribute(ACCOUNT_SCHEMA_FACTORY_NAME);

        if (name == null) {
            name = getRole(policy, request);
        }

        if (name != null) {
            SchemaFactory schemaFactory = schemaFactories.get(name.toString());
            if (schemaFactory == null) {
                log.error("Failed to find schema factory [{}]", name);
            } else {
                return name.toString();
            }
        }
        Account authenticatedAsAccount = accountDao.getAccountById(policy.getAuthenticatedAsAccountId());
        Policy authenticatedAsPolicy = getPolicy(authenticatedAsAccount, authenticatedAsAccount, policy.getIdentities(), request);
        String role = authDao.getRole(account, policy, authenticatedAsPolicy);
        SchemaFactory schemaFactory = schemaFactories.get(role);
        if (schemaFactory != null) {
            return role;
        }
        return null;
    }

    @Override
    public Policy getPolicy(Account account, Account authenticatedAsAccount, Set<Identity> identities, ApiRequest request) {
        PolicyOptionsWrapper options = new PolicyOptionsWrapper(optionsFactory.getOptions(account));
        AccountPolicy policy = new AccountPolicy(account, authenticatedAsAccount, identities, options);

        String kind = getRole(policy, request);
        if (kind != null) {
            options = new PolicyOptionsWrapper(optionsFactory.getOptions(kind));
            options.setOption(Policy.ASSIGNED_ROLE, kind);
            policy = new AccountPolicy(account, authenticatedAsAccount, identities, options);
        }

        applyRequiredSystemAccountOptions(account, options);
        policy = new AccountPolicy(account, authenticatedAsAccount, identities, options);

        if (SubscriptionUtils.getSubscriptionStyle(policy) == SubscriptionStyle.QUALIFIED) {
            options.setOption(SubscriptionUtils.POLICY_SUBSCRIPTION_QUALIFIER, IaasEvents.ACCOUNT_QUALIFIER);
            options.setOption(SubscriptionUtils.POLICY_SUBSCRIPTION_QUALIFIER_VALUE, Long.toString(account.getId()));
        }

        return policy;
    }

    protected void applyRequiredSystemAccountOptions(Account account, PolicyOptionsWrapper options) {
        if (account == null) {
            return;
        }

        String kind = account.getKind();
        if (!AccountConstants.SERVICE_KIND.equals(kind) && !AccountConstants.SUPER_ADMIN_KIND.equals(kind)) {
            return;
        }

        /*
         * Rancher 1.6 external handlers subscribe to exact names such as
         * stack.create;handler=rancher-compose-executor.  If persisted settings
         * are missing or polluted during a legacy migration, Archaius falls back
         * to QUALIFIED subscriptions and those handlers silently stop receiving
         * events.  These two account kinds were raw/all-account in the original
         * defaults, so keep that contract explicit in code for upgrade safety.
         */
        options.setOption(SubscriptionUtils.POLICY_SUBSCRIPTION_STYLE, SubscriptionStyle.RAW.name().toLowerCase());
        options.setOption(Policy.AUTHORIZED_FOR_ALL_ACCOUNTS, "true");
        options.setOption(Policy.LIST_ALL_ACCOUNTS, "true");
        options.setOption(Policy.LIST_ALL_SETTINGS, "true");
        options.setOption(Policy.PLAIN_ID_OPTION, "true");
    }

    protected String getRole(Policy policy, ApiRequest request) {
        return policy.getOption(Policy.ASSIGNED_ROLE);
    }

    public static SubscriptionStyle getSubscriptionStyle(Account account, AchaiusPolicyOptionsFactory optionsFactory) {
        Policy tempPolicy = new AccountPolicy(account, account, null, optionsFactory.getOptions(account));
        return SubscriptionUtils.getSubscriptionStyle(tempPolicy);
    }

    public List<SchemaFactory> getSchemaFactoryList() {
        return schemaFactoryList;
    }

    @Inject
    public void setSchemaFactoryList(List<SchemaFactory> schemaFactoryList) {
        this.schemaFactoryList = schemaFactoryList;
    }

    @Override
    public void start() {
        for (SchemaFactory factory : schemaFactoryList) {
            if (factory instanceof SubSchemaFactory) {
                ((SubSchemaFactory) factory).init();
            }
            schemaFactories.put(factory.getId(), factory);
        }
    }

    @Override
    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = priority;
    }

    public AchaiusPolicyOptionsFactory getOptionsFactory() {
        return optionsFactory;
    }

    @Inject
    public void setOptionsFactory(AchaiusPolicyOptionsFactory optionsFactory) {
        this.optionsFactory = optionsFactory;
    }

}
