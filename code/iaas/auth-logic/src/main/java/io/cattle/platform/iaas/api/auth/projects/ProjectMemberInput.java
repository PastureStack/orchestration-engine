package io.cattle.platform.iaas.api.auth.projects;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProjectMemberInput {

    static final String MEMBERS = "members";

    private static final String EXTERNAL_ID = "externalId";
    private static final String EXTERNAL_ID_TYPE = "externalIdType";
    private static final String ROLE = "role";

    private ProjectMemberInput() {
    }

    static Object requestField(Object requestObject, String field) {
        Map<?, ?> requestMap = Map.class.cast(requestObject);
        return requestMap.get(field);
    }

    static List<Map<String, String>> memberMapList(Object value) {
        if (value == null) {
            return null;
        }

        List<?> source = List.class.cast(value);
        List<Map<String, String>> result = new ArrayList<Map<String, String>>(source.size());
        for (Object entry : source) {
            result.add(memberMap(entry));
        }
        return result;
    }

    private static Map<String, String> memberMap(Object value) {
        Map<?, ?> source = Map.class.cast(value);
        Map<String, String> result = new LinkedHashMap<String, String>();

        copyStringValue(source, result, EXTERNAL_ID);
        copyStringValue(source, result, EXTERNAL_ID_TYPE);
        copyStringValue(source, result, ROLE);
        return result;
    }

    private static void copyStringValue(Map<?, ?> source, Map<String, String> target, String key) {
        if (source.containsKey(key)) {
            Object value = source.get(key);
            target.put(key, value == null ? null : String.class.cast(value));
        }
    }
}
