package io.cattle.platform.framework.encryption;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;


public class EncryptionConstants {
    public static final String ENCRYPTER_NAME_DELM = ":";
    public static final ConfigProperty<String> ENCRYPTER_NAME = ArchaiusUtil.getStringProperty("api.encryption.encrypter");
    public static final ConfigProperty<String> HASHER_NAME = ArchaiusUtil.getStringProperty("api.encryption.hasher");

    public static final String HASH = "hash";
    public static final String ENCRYPT = "encrypt";
}
