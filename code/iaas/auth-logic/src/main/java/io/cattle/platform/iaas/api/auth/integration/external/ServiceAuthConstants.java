package io.cattle.platform.iaas.api.auth.integration.external;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

public class ServiceAuthConstants {
    public static ConfigProperty<String> AUTH_SERVICE_URL = ArchaiusUtil.getStringProperty("system.stack.auth.url");
    public static final String ACCEPT = "Accept";
    public static final String ACCESS_TOKEN = "access_token";
    public static final String AUTH_ERROR = "AuthError";
    public static final String JWT_KEY = "jwt";
    public static final String AUTHORIZATION = "Authorization";
    public static final String APPLICATION_JSON = "application/json";
    public static final String APPLICATION_FORM_URL_ENCODED = "application/x-www-form-urlencoded";
    public static final String ACCESSMODE_SETTING = "api.auth.access.mode";
    public static final String ALLOWED_IDENTITIES_SETTING = "api.auth.allowed.identities";
    public static final String USERTYPE_SETTING = "api.auth.user.type";
    public static final String IDENTITY_SEPARATOR_SETTING = "api.auth.external.provider.identity.separator";
    public static final String EXTERNAL_AUTH_PROVIDER_SETTING = "api.auth.external.provider.configured";
    public static final String JWT_CREATION_FAILED = "FailedToMakeJWT";
    public static final String NO_IDENTITY_LOOKUP_SETTING = "api.auth.external.provider.no.identity.lookup";

    public static final ConfigProperty<String> ACCESS_MODE = ArchaiusUtil.getStringProperty(ACCESSMODE_SETTING);
    public static final ConfigProperty<String> ALLOWED_IDENTITIES = ArchaiusUtil.getStringProperty(ALLOWED_IDENTITIES_SETTING);
    public static final ConfigProperty<String> USER_TYPE = ArchaiusUtil.getStringProperty(USERTYPE_SETTING);
    public static final ConfigProperty<String> IDENTITY_SEPARATOR = ArchaiusUtil.getStringProperty(IDENTITY_SEPARATOR_SETTING);
    public static final ConfigProperty<Boolean> IS_EXTERNAL_AUTH_PROVIDER = ArchaiusUtil.getBooleanProperty(EXTERNAL_AUTH_PROVIDER_SETTING);
    public static final ConfigProperty<Boolean> NO_IDENTITY_LOOKUP_SUPPORTED = ArchaiusUtil.getBooleanProperty(NO_IDENTITY_LOOKUP_SETTING);

}
