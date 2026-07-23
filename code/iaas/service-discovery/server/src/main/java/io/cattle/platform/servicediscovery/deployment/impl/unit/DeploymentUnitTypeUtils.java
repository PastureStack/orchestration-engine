package io.cattle.platform.servicediscovery.deployment.impl.unit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.tuple.Pair;

final class DeploymentUnitTypeUtils {

    private DeploymentUnitTypeUtils() {
    }

    static List<String> stringList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<String>();
        List<?> values = List.class.cast(value);
        for (Object item : values) {
            result.add(String.class.cast(item));
        }
        return result;
    }

    static List<Integer> integerList(Object value) {
        if (value == null) {
            return Collections.emptyList();
        }

        List<Integer> result = new ArrayList<Integer>();
        List<?> values = List.class.cast(value);
        for (Object item : values) {
            result.add(Integer.class.cast(item));
        }
        return result;
    }

    static Map<String, String> stringMap(Object value) {
        if (value == null) {
            return Collections.emptyMap();
        }

        Map<String, String> result = new LinkedHashMap<String, String>();
        Map<?, ?> labels = Map.class.cast(value);
        for (Map.Entry<?, ?> label : labels.entrySet()) {
            result.put(String.class.cast(label.getKey()), String.class.cast(label.getValue()));
        }
        return result;
    }

    static Pair<String, String> stringPair(Object value) {
        Pair<?, ?> pair = Pair.class.cast(value);
        return Pair.of(String.class.cast(pair.getLeft()), String.class.cast(pair.getRight()));
    }
}
