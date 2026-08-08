package io.cattle.platform.object.util;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ObjectLaunchConfigurationUtils {

    private static final Map<String, ConfigProperty<Integer>> PROCESS_PRIORITIES = new ConcurrentHashMap<String, ConfigProperty<Integer>>();

    public static LaunchConfiguration createConfig(SchemaFactory factory, String processName, Object resource, Map<String, Object> data) {
        Schema schema = factory.getSchema(resource.getClass());

        if (schema == null) {
            throw new IllegalArgumentException("Failed to find schema for [" + resource + "]");
        }

        Field field = schema.getResourceFields().get(ObjectMetaDataManager.ID_FIELD);

        if (field == null) {
            throw new IllegalStateException("Schema [" + schema.getId() + "] does not have an ID field so we can not launch a process for it");
        }

        Object id = field.getValue(resource);

        if (id == null) {
            throw new IllegalStateException("Object [" + resource + "] has a null ID");
        }

        String[] parts = processName.split("[.]");
        int priority = processPriority("process." + processName + ".priority");
        if (priority == 0) {
            priority = processPriority("process." + parts[parts.length-1] + ".priority");
        }
        if (priority == 0) {
            priority = processPriority("process." + parts[0] + ".priority");
        }

        boolean isSystem = ObjectUtils.isSystem(resource);

        Map<String, Object> processData = new HashMap<>();
        if (data != null) {
            processData.putAll(data);
        }

        if (priority >= 0 && isSystem) {
            priority += 1000;
            processData.put(ObjectMetaDataManager.SYSTEM_FIELD, true);
        }

        return new LaunchConfiguration(processName, schema.getId(), id.toString(), ObjectUtils.getAccountId(resource), priority,
                processData);
    }


    protected static int processPriority(String key) {
        ConfigProperty<Integer> property = PROCESS_PRIORITIES.get(key);
        if (property == null) {
            property = ArchaiusUtil.getIntProperty(key);
            ConfigProperty<Integer> existing = PROCESS_PRIORITIES.putIfAbsent(key, property);
            if (existing != null) {
                property = existing;
            }
        }
        return property.get();
    }

}
