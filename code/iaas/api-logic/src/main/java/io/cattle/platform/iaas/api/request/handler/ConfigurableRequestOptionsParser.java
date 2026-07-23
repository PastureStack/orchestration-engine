package io.cattle.platform.iaas.api.request.handler;

import io.github.ibuildthecloud.gdapi.request.handler.RequestOptionsParser;

import java.util.List;


public class ConfigurableRequestOptionsParser extends RequestOptionsParser {

    private static final RequestOptionsSettings DEFAULT_SETTINGS = ArchaiusRequestOptionsSettings.create();

    private final RequestOptionsSettings settings;

    public ConfigurableRequestOptionsParser() {
        this(DEFAULT_SETTINGS);
    }

    ConfigurableRequestOptionsParser(RequestOptionsSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("Request options settings are required");
        }
        this.settings = settings;
    }

    @Override
    public List<String> getOptions() {
        return settings.options();
    }

}
