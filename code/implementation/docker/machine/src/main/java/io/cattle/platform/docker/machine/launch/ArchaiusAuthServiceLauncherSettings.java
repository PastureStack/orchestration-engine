package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.integration.external.ServiceAuthConstants;

public class ArchaiusAuthServiceLauncherSettings implements AuthServiceLauncherSettings {

    private static final ConfigProperty<String> AUTH_SERVICE_BINARY = ArchaiusUtil.getStringProperty("auth.service.executable");
    private static final ConfigProperty<Boolean> LAUNCH_AUTH_SERVICE = ArchaiusUtil.getBooleanProperty("auth.service.execute");
    private static final ConfigProperty<String> SECURITY_SETTING = ArchaiusUtil.getStringProperty("api.security.enabled");
    private static final ConfigProperty<String> EXTERNAL_AUTH_PROVIDER_SETTING = ArchaiusUtil.getStringProperty("api.auth.external.provider.configured");
    private static final ConfigProperty<String> NO_IDENTITY_LOOKUP_SETTING = ArchaiusUtil.getStringProperty("api.auth.external.provider.no.identity.lookup");
    private static final ConfigProperty<String> AUTH_SERVICE_LOG_LEVEL = ArchaiusUtil.getStringProperty("auth.service.log.level");
    private static final ConfigProperty<String> AUTH_SERVICE_CONFIG_UPDATE_TIMESTAMP = ArchaiusUtil.getStringProperty("auth.service.config.update.timestamp");
    private static final ConfigProperty<String> API_AUTH_SHIBBOLETH_REDIRECT_WHITELIST_SETTING = ArchaiusUtil
            .getStringProperty("api.auth.shibboleth.redirect.whitelist");

    public static AuthServiceLauncherSettings create() {
        return new ArchaiusAuthServiceLauncherSettings();
    }

    @Override
    public String authServiceExecutable() {
        return AUTH_SERVICE_BINARY.get();
    }

    @Override
    public boolean launchAuthService() {
        return LAUNCH_AUTH_SERVICE.get();
    }

    @Override
    public String authServiceUrl() {
        return ServiceAuthConstants.AUTH_SERVICE_URL.get();
    }

    @Override
    public ConfigProperty<String> authEnablerProperty() {
        return SecurityConstants.AUTH_ENABLER_SETTING;
    }

    @Override
    public ConfigProperty<String> securityProperty() {
        return SECURITY_SETTING;
    }

    @Override
    public ConfigProperty<String> externalAuthProviderProperty() {
        return EXTERNAL_AUTH_PROVIDER_SETTING;
    }

    @Override
    public ConfigProperty<String> noIdentityLookupProperty() {
        return NO_IDENTITY_LOOKUP_SETTING;
    }

    @Override
    public ConfigProperty<String> authServiceLogLevelProperty() {
        return AUTH_SERVICE_LOG_LEVEL;
    }

    @Override
    public ConfigProperty<String> authServiceConfigUpdateTimestampProperty() {
        return AUTH_SERVICE_CONFIG_UPDATE_TIMESTAMP;
    }

    @Override
    public ConfigProperty<String> shibbolethRedirectWhitelistProperty() {
        return API_AUTH_SHIBBOLETH_REDIRECT_WHITELIST_SETTING;
    }

}
