package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ConfigProperty;

public interface AuthServiceLauncherSettings {

    String authServiceExecutable();

    boolean launchAuthService();

    String authServiceUrl();

    ConfigProperty<String> authEnablerProperty();

    ConfigProperty<String> securityProperty();

    ConfigProperty<String> externalAuthProviderProperty();

    ConfigProperty<String> noIdentityLookupProperty();

    ConfigProperty<String> authServiceLogLevelProperty();

    ConfigProperty<String> authServiceConfigUpdateTimestampProperty();

    ConfigProperty<String> shibbolethRedirectWhitelistProperty();

}
