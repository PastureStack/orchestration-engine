package io.cattle.platform.framework.encryption.impl;

import io.cattle.platform.framework.encryption.EncryptionUtils;
import io.github.ibuildthecloud.gdapi.model.Transformer;
import org.apache.commons.lang3.StringUtils;

public class NoOpTransformer implements Transformer {

    private static final String METADATA = "***";
    private static final String NAME = "NoOp";

    @Override
    public String transform(String value) {
        if (StringUtils.isBlank(value)){
            return "";
        }
        return METADATA + StringUtils.reverse(value) + StringUtils.reverse(METADATA);
    }

    @Override
    public String untransform(String value) {
        if (StringUtils.isBlank(value)){
            return "";
        }
        String stripped = removeStart(value, METADATA);
        stripped = removeEnd(stripped, StringUtils.reverse(METADATA));
        return StringUtils.reverse(stripped);
    }

    @Override
    public boolean compare(String plainText, String transformed) {
        return EncryptionUtils.isEqual(plainText, untransform(transformed));
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public void init() {
    }

    private static String removeStart(String value, String prefix) {
        if (value == null || prefix == null || prefix.isEmpty()) {
            return value;
        }
        if (value.startsWith(prefix)) {
            return value.substring(prefix.length());
        }
        return value;
    }

    private static String removeEnd(String value, String suffix) {
        if (value == null || suffix == null || suffix.isEmpty()) {
            return value;
        }
        if (value.endsWith(suffix)) {
            return value.substring(0, value.length() - suffix.length());
        }
        return value;
    }
}
