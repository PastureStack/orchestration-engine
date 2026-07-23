package io.cattle.platform.api.servlet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;

public class SecurityHeaders {

    private static final SecurityHeaderSettings DEFAULT_SETTINGS = ArchaiusSecurityHeaderSettings.create();

    private final SecurityHeaderSettings settings;

    public SecurityHeaders() {
        this(DEFAULT_SETTINGS);
    }

    SecurityHeaders(SecurityHeaderSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings are required");
        }

        this.settings = settings;
    }

    public void apply(HttpServletRequest request, HttpServletResponse response) {
        if (!settings.enabled()) {
            return;
        }

        setIfNotBlank(response, "X-Frame-Options", settings.frameOptions());
        setIfNotBlank(response, "Content-Security-Policy", settings.contentSecurityPolicy());
        setIfNotBlank(response, "X-Content-Type-Options", settings.contentTypeOptions());
        setIfNotBlank(response, "Referrer-Policy", settings.referrerPolicy());

        if (settings.hstsEnabled() && isSecureRequest(request)) {
            setIfNotBlank(response, "Strict-Transport-Security", settings.hsts());
        }
    }

    protected boolean isSecureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }

        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null) {
            for (String value : forwardedProto.split(",")) {
                if ("https".equalsIgnoreCase(value.trim())) {
                    return true;
                }
            }
        }

        String forwarded = request.getHeader("Forwarded");
        if (forwarded != null) {
            for (String part : forwarded.split(";")) {
                String value = part.trim();
                if (value.regionMatches(true, 0, "proto=https", 0, "proto=https".length())) {
                    return true;
                }
            }
        }

        return false;
    }

    private void setIfNotBlank(HttpServletResponse response, String name, String value) {
        if (StringUtils.isNotBlank(value)) {
            response.setHeader(name, value);
        }
    }
}
