package io.cattle.platform.archaius.util;

import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicDoubleProperty;
import com.netflix.config.DynamicFloatProperty;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicLongProperty;
import com.netflix.config.DynamicStringListProperty;
import com.netflix.config.DynamicStringProperty;

interface LegacyDynamicPropertyProvider {

    DynamicLongProperty getLong(String key, long defaultValue);

    DynamicIntProperty getInt(String key, int defaultValue);

    DynamicBooleanProperty getBoolean(String key, boolean defaultValue);

    DynamicDoubleProperty getDouble(String key, double defaultValue);

    DynamicFloatProperty getFloat(String key, float defaultValue);

    DynamicStringProperty getString(String key, String defaultValue);

    DynamicStringListProperty getList(String key, String defaultValue);

}
