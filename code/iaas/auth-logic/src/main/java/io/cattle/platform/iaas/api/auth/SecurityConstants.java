package io.cattle.platform.iaas.api.auth;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.iaas.api.auth.integration.azure.AzureConstants;
import io.cattle.platform.iaas.api.auth.integration.ldap.OpenLDAP.OpenLDAPConstants;
import io.cattle.platform.iaas.api.auth.integration.local.LocalAuthConstants;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class SecurityConstants {

    public static final String ENABLED = "enabled";
    public static final String SECURITY_SETTING = "api.security.enabled";
    public static final ConfigProperty<Boolean> SECURITY = ArchaiusUtil.getBooleanProperty(SECURITY_SETTING);
    public static final String AUTH_PROVIDER_SETTING = "api.auth.provider.configured";
    public static final ConfigProperty<String> AUTH_PROVIDER = ArchaiusUtil.getStringProperty(AUTH_PROVIDER_SETTING);
    public static final String ROLE_SETTING_BASE = "api.security.role.priority.";


    public static final String NO_PROVIDER = "none";
    public static final String CODE = "code";
    public static final String TOKEN_VERSION = "v1";
    public static final ConfigProperty<Long> TOKEN_EXPIRY_MILLIS = ArchaiusUtil.getLongProperty("api.auth.jwt.token.expiry");
    public static final String HAS_LOGGED_IN = "hasLoggedIn";
    public static final String AUTH_ENABLER = "api.auth.enabler";
    public static final ConfigProperty<String> AUTH_ENABLER_SETTING = ArchaiusUtil.getStringProperty(AUTH_ENABLER);
    public static final List<String> INTERNAL_AUTH_PROVIDERS = Collections.unmodifiableList(
            Arrays.asList(new String[] {AzureConstants.CONFIG, OpenLDAPConstants.CONFIG, LocalAuthConstants.CONFIG}));
}
