package io.cattle.platform.iaas.api.auth.integration.azure;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class AzureConstants {



    public static final String NAME = "azuread";
    public static final String CONFIG = NAME + "config";

    public static final String ACCEPT = "Accept";
    public static final String ACCESS_TOKEN = "access_token";

    public static final String APPLICATION_JSON = "application/json";
    public static final String APPLICATION_FORM_URL_ENCODED = "application/x-www-form-urlencoded";
    public static final String AUTHORIZATION = "Authorization";


    public static final String AUTHORITY = "https://login.windows.net/common/oauth2/token";
    public static final String GRAPH_API_ENDPOINT = "https://graph.windows.net/";

    public static final String GRAPH_API_VERSION = "?api-version=1.6";

    public static final String CLIENT_ID = "client_id";
    public static final String CLIENT_ID_SETTING = "api.auth.azure.client.id";
    public static final String TENANT_ID = "tenant_id";
    public static final String TENANT_ID_SETTING = "api.auth.azure.tenant.id";
    public static final String DOMAIN = "domain";
    public static final String DOMAIN_SETTING = "api.auth.azure.domain";
    public static final String ACCESSMODE_SETTING = "api.auth.azure.access.mode";
    public static final String ADMIN_USERNAME_SETTING = "api.auth.azure.admin.username";
    public static final String ADMIN_PASSWORD_SETTING = "api.auth.azure.admin.password";

    public static final String AZURE_ACCESS_TOKEN = NAME + "access_token";
    public static final String AZURE_REFRESH_TOKEN = NAME + "refresh_token";

    public static final String GROUP_SCOPE = NAME + "_group";
    public static final String USER_SCOPE = NAME + "_user";

    public static final ConfigProperty<String> AZURE_CLIENT_ID = ArchaiusUtil.getStringProperty(CLIENT_ID_SETTING);
    public static final ConfigProperty<String> AZURE_TENANT_ID = ArchaiusUtil.getStringProperty(TENANT_ID_SETTING);
    public static final ConfigProperty<String> AZURE_DOMAIN = ArchaiusUtil.getStringProperty(DOMAIN_SETTING);
    public static final ConfigProperty<String> ACCESS_MODE = ArchaiusUtil.getStringProperty(ACCESSMODE_SETTING);
    public static final ConfigProperty<String> AZURE_ADMIN_USERNAME = ArchaiusUtil.getStringProperty(ADMIN_USERNAME_SETTING);
    public static final ConfigProperty<String> AZURE_ADMIN_PASSWORD = ArchaiusUtil.getStringProperty(ADMIN_PASSWORD_SETTING);

    public static final Set<String> SCOPES = Collections.unmodifiableSet( new HashSet<>(Arrays.asList(
            USER_SCOPE, GROUP_SCOPE
    )));
    public static final String AZURE_CLIENT =     NAME + "Client";
    public static final String AZURE_ERROR = "AzureError";
    public static final String MANAGER = NAME + "Manager";
    public static final String SEARCH_PROVIDER = NAME + "Provider";
    public static final String TOKEN_CREATOR = NAME + "TokenCreator";
    public static final String TRANSFORMATION_HANDLER = NAME + "TransformationHandler";
    public static final String ACCOUNT_LOOKUP = NAME + "AccountLookUp";
    public static final String AZURE_JWT = NAME + "jwt";
    public static final String JWT_CREATION_FAILED = "FailedToMakeJWT";



}
