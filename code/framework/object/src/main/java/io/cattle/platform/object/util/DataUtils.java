package io.cattle.platform.object.util;

import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.beanutils2.BeanUtils;
import org.apache.commons.beanutils2.ConvertUtils;

public class DataUtils {

    public static final String DATA = "data";
    public static final String OPTIONS = "options";
    public static final String FIELDS = "fields";

    public static String getState(Object obj) {
        try {
            return BeanUtils.getProperty(obj, ObjectMetaDataManager.STATE_FIELD);
        } catch (IllegalAccessException e) {
        } catch (InvocationTargetException e) {
        } catch (NoSuchMethodException e) {
        }

        return null;
    }

    public static Map<String, Object> getFields(Object obj) {
        Map<String, Object> data = DataAccessor.getData(obj, true);
        Map<String, Object> fields = CollectionUtils.toMap(data.get(FIELDS));
        return Collections.unmodifiableMap(fields);
    }

    public static Map<String, Object> getWritableFields(Object obj) {
        Map<String, Object> data = DataAccessor.getData(obj, false);
        Object fieldsObject = data.get(FIELDS);
        Map<String, Object> fields = fieldsObject == null ? null : CollectionUtils.castMap(fieldsObject);

        if (fields == null) {
            fields = new HashMap<String, Object>();
            data.put(FIELDS, fields);
        }

        return fields;
    }

    protected static void setData(Object obj, Map<String, Object> data) {
        ObjectUtils.setPropertyIgnoreErrors(obj, DATA, data);
    }

    public static <T> List<T> getFieldList(Map<String, Object> data, String name, Class<T> type) {
        Map<String, Object> fields = CollectionUtils.castMap(data.get(FIELDS));
        Object value = fields.get(name);

        if (value == null) {
            return null;
        }

        if (value instanceof List<?> list) {
            List<T> result = new ArrayList<T>(list.size());
            for (Object obj : list) {
                result.add(convert(obj, type));
            }
            return result;
        } else {
            throw new IllegalArgumentException("[" + value + "] is not a list");
        }
    }

    public static <T> T getFieldFromRequest(ApiRequest request, String name, Class<T> type) {
        if (request == null) {
            return null;
        }

        Map<String, Object> fields = CollectionUtils.castMap(request.getRequestObject());
        Object value = fields.get(name);

        if (value == null) {
            return null;
        }

        return convert(value, type);
    }

    private static <T> T convert(Object value, Class<T> type) {
        if (value == null || type.isInstance(value)) {
            return type.cast(value);
        }

        return type.cast(ConvertUtils.convert(value, type));
    }
}
