package io.cattle.platform.engine.process.util;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;


public class ProcessEngineUtils {

    private static final ConfigProperty<Boolean> PROVISIONING_ENABLED = ArchaiusUtil.getBooleanProperty("provisioning.enabled");

    public static boolean enabled() {
        return PROVISIONING_ENABLED.get();
    }

}