package io.cattle.platform.api.servlet;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

final class ArchaiusSecurityHeaderSettings implements SecurityHeaderSettings {

    private static final String ENABLED_KEY = "api.security.headers.enabled";
    private static final String FRAME_OPTIONS_KEY = "api.security.headers.frame.options";
    private static final String CONTENT_SECURITY_POLICY_KEY = "api.security.headers.content.security.policy";
    private static final String CONTENT_TYPE_OPTIONS_KEY = "api.security.headers.content.type.options";
    private static final String REFERRER_POLICY_KEY = "api.security.headers.referrer.policy";
    private static final String HSTS_ENABLED_KEY = "api.security.headers.hsts.enabled";
    private static final String HSTS_KEY = "api.security.headers.hsts";

    private final ConfigProperty<Boolean> enabled = ArchaiusUtil.getBooleanProperty(ENABLED_KEY, true);
    private final ConfigProperty<String> frameOptions = ArchaiusUtil.getStringProperty(FRAME_OPTIONS_KEY, "SAMEORIGIN");
    private final ConfigProperty<String> contentSecurityPolicy = ArchaiusUtil.getStringProperty(
            CONTENT_SECURITY_POLICY_KEY, "frame-ancestors 'self'");
    private final ConfigProperty<String> contentTypeOptions = ArchaiusUtil.getStringProperty(CONTENT_TYPE_OPTIONS_KEY,
            "nosniff");
    private final ConfigProperty<String> referrerPolicy = ArchaiusUtil.getStringProperty(REFERRER_POLICY_KEY,
            "no-referrer");
    private final ConfigProperty<Boolean> hstsEnabled = ArchaiusUtil.getBooleanProperty(HSTS_ENABLED_KEY, true);
    private final ConfigProperty<String> hsts = ArchaiusUtil.getStringProperty(HSTS_KEY,
            "max-age=31536000; includeSubDomains");

    static SecurityHeaderSettings create() {
        return new ArchaiusSecurityHeaderSettings();
    }

    @Override
    public boolean enabled() {
        return enabled.get();
    }

    @Override
    public String frameOptions() {
        return frameOptions.get();
    }

    @Override
    public String contentSecurityPolicy() {
        return contentSecurityPolicy.get();
    }

    @Override
    public String contentTypeOptions() {
        return contentTypeOptions.get();
    }

    @Override
    public String referrerPolicy() {
        return referrerPolicy.get();
    }

    @Override
    public boolean hstsEnabled() {
        return hstsEnabled.get();
    }

    @Override
    public String hsts() {
        return hsts.get();
    }

}
