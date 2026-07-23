package io.cattle.platform.iaas.api.auth.integration.local;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.iaas.api.auth.integration.util.AuthHttpClient;
import io.cattle.platform.iaas.api.auth.integration.util.AuthHttpClient.Response;
import io.cattle.platform.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LocalAuthPasswordValidator {

    public static final ConfigProperty<String> AUTH_VALIDATE_URL = ArchaiusUtil.getStringProperty("api.auth.local.validate.url");
    public static final ConfigProperty<Integer> AUTH_VALIDATE_TIMEOUT = ArchaiusUtil.getIntProperty("api.auth.local.validate.timeout.milliseconds");

    final static Logger log = LoggerFactory.getLogger(LocalAuthPasswordValidator.class);

    public static void validatePassword(String password, JsonMapper jsonMapper) {
        String authValidateUrl = AUTH_VALIDATE_URL.get();
        if (StringUtils.isBlank(authValidateUrl)) {
            return;
        }

        Map<String, String> data = new HashMap<String, String>();
        data.put("secret", password);
        String jsonString = "";
        Integer code;
        Response response = null;

        try {
            jsonString = jsonMapper.writeValueAsString(data);
        } catch (IOException e) {
            log.error("Error in creating json for POST request", e);
        }

        try {
            int timeout = AUTH_VALIDATE_TIMEOUT.get();
            response = AuthHttpClient.postJson(authValidateUrl, jsonString, timeout);
        } catch (IOException e) {
            log.error("Error sending POST request", e);
            throw new ClientVisibleException(ResponseCodes.INTERNAL_SERVER_ERROR, "Error sending POST request");
        }

        code = response.getStatusCode();
        if (code >=400 && code <= 499) {
            Map<String, Object> jsonData = new HashMap<String, Object>();
            try {
                jsonData = jsonMapper.readValue(response.getBody());
            } catch (IOException e) {
                log.error("No JSON response from validator", e);
            }

            if (!jsonData.containsKey("type") || !jsonData.containsKey("message")) {
                throw new ClientVisibleException(code, "Incomplete JSON response");
            }

            if (jsonData.get("type") != null) {
                throw new ClientVisibleException(code, (String) jsonData.get("message"));
            }
        } else if (code < 200 || code > 299) {
            throw new ClientVisibleException(ResponseCodes.INTERNAL_SERVER_ERROR, "Error talking to validator");
        }
    }
}
