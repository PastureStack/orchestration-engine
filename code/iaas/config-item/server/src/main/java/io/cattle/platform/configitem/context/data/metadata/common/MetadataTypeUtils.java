package io.cattle.platform.configitem.context.data.metadata.common;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MetadataTypeUtils {

    private MetadataTypeUtils() {
    }

    static List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value == null) {
            return result;
        }

        List<?> values = List.class.cast(value);
        for (Object item : values) {
            result.add(String.class.cast(item));
        }
        return result;
    }

    static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        if (value == null) {
            return result;
        }

        Map<?, ?> values = Map.class.cast(value);
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            result.put(String.class.cast(entry.getKey()), String.class.cast(entry.getValue()));
        }
        return result;
    }
}
