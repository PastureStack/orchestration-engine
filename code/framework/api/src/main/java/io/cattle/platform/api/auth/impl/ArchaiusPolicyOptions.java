package io.cattle.platform.api.auth.impl;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ArchaiusPolicyOptions implements PolicyOptions {

    private static String PROP_FORMAT = "account.type.%s.%s";

    Map<String, ConfigProperty<Boolean>> bools = new ConcurrentHashMap<String, ConfigProperty<Boolean>>();
    Map<String, ConfigProperty<String>> strings = new ConcurrentHashMap<String, ConfigProperty<String>>();
    Map<String, OptionCallback> callbacks = new HashMap<String, OptionCallback>();
    String name;

    public ArchaiusPolicyOptions(String name) {
        this.name = name;
    }

    @Override
    public boolean isOption(String optionName) {
        ConfigProperty<Boolean> prop = bools.get(optionName);
        if (prop == null) {
            prop = ArchaiusUtil.getBooleanProperty(String.format(PROP_FORMAT, name, optionName));
            bools.put(optionName, prop);
        }
        return prop.get();
    }

    @Override
    public String getOption(String optionName) {
        OptionCallback callback = callbacks.get(optionName);
        if (callback != null) {
            return callback.getOption();
        }

        ConfigProperty<String> prop = strings.get(optionName);
        if (prop == null) {
            prop = ArchaiusUtil.getStringProperty(String.format(PROP_FORMAT, name, optionName));
            strings.put(optionName, prop);
        }
        return prop.get();
    }

    @Override
    public void setOption(String name, String value) {
        throw new UnsupportedOperationException();
    }

}
