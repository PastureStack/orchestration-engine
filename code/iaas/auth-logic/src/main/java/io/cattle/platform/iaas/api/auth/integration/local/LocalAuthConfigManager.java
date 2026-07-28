package io.cattle.platform.iaas.api.auth.integration.local;

import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.util.SettingsUtils;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.dao.PasswordDao;
import io.cattle.platform.iaas.api.auth.mfa.MfaService;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

public class LocalAuthConfigManager extends AbstractNoOpResourceManager {

    @Inject
    PasswordDao passwordDao;

    @Inject
    AuthDao authDao;

    @Inject
    AccountDao accountDao;

    @Inject
    MfaService mfaService;

    @Inject
    SettingsUtils settingsUtils;

    @Inject
    JsonMapper jsonMapper;

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[] {LocalAuthConfig.class};
    }

    @Override
    protected Object createInternal(String type, ApiRequest request) {
        if (!Strings.CI.equals(LocalAuthConstants.CONFIG, request.getType())) {
            return null;
        }
        Map<String, Object> config = CollectionUtils.toMap(request.getRequestObject());

        String username = (String) config.get("username");
        String name = (String) config.get("name");
        String password = (String) config.get("password");
        String accessMode = (String) config.get("accessMode");
        Boolean enabled = (Boolean) config.get("enabled");

        if (enabled == null) {
            settingsUtils.changeSetting(SecurityConstants.SECURITY_SETTING, false);
            settingsUtils.changeSetting(SecurityConstants.AUTH_PROVIDER_SETTING, SecurityConstants.NO_PROVIDER);
            return new LocalAuthConfig("", "", "", accessMode, false);
        } else {
            settingsUtils.changeSetting(SecurityConstants.SECURITY_SETTING, enabled);
            if (StringUtils.isNotBlank(username)) {
                LocalAuthPasswordValidator.validatePassword(password, jsonMapper);
                passwordDao.verifyUsernamePassword(username, password, name);
                Account account = authDao.getAccountByLogin(username, password,
                        ApiContext.getContext().getTransformationService());
                if (account == null || !accountDao.isActiveAccount(account)
                        || !AccountConstants.ADMIN_KIND.equalsIgnoreCase(account.getKind())) {
                    throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED,
                            "InvalidLocalAdministrator",
                            "The supplied credentials do not belong to an active system administrator.",
                            null);
                }
                // Retain this verified local administrator credential as an
                // explicit break-glass path when an external provider is
                // selected later.  This setting does not replace that provider.
                settingsUtils.changeSetting(LocalAuthConstants.RECOVERY_ENABLED_SETTING, true);
                settingsUtils.changeSetting(LocalAuthConstants.RECOVERY_VERIFIED_AT_SETTING,
                        System.currentTimeMillis());
                settingsUtils.changeSetting(LocalAuthConstants.RECOVERY_MFA_READY_SETTING,
                        mfaService.hasPrimaryFactor(account.getId()));
            }
        }

        settingsUtils.changeSetting(SecurityConstants.AUTH_PROVIDER_SETTING, LocalAuthConstants.CONFIG);
        settingsUtils.changeSetting(LocalAuthConstants.ACCESS_MODE_SETTING, accessMode);

        return new LocalAuthConfig(username, name, password, accessMode, enabled);
    }

    @Override
    protected Object listInternal(SchemaFactory schemaFactory, String type, Map<Object, Object> criteria, ListOptions options) {
        return new LocalAuthConfig("", "", "", LocalAuthConstants.ACCESS_MODE.get(), SecurityConstants.SECURITY.get());
    }
}
