package io.cattle.platform.extension.dynamic;

import io.cattle.platform.extension.impl.ExtensionManagerImpl;
import io.cattle.platform.util.type.PriorityUtils;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class DynamicExtensionManager extends ExtensionManagerImpl {

    private static final String DYNAMIC_HANDLER_KEY = "dynamic.extension.handler";

    @Override
    public <T> List<T> getExtensionList(String key, Class<T> type) {
        List<T> extensions = super.getExtensionList(key, type);
        Class<?> extensionType = type;
        if (type == null) {
            extensionType = getExpectedType(key);
        }

        if (DYNAMIC_HANDLER_KEY.equals(key) || DynamicExtensionHandler.class == extensionType) {
            return extensions;
        }

        for (DynamicExtensionHandler handler : getExtensionList(DynamicExtensionHandler.class)) {
            List<T> additional = extensionList(handler.getExtensionList(key, extensionType));

            if (additional.size() == 0) {
                continue;
            }

            List<T> merged = new ArrayList<T>(extensions.size() + additional.size());
            Iterator<T> iter = additional.iterator();
            T current = iter.next();

            for (T obj : extensions) {
                while (current != null && PriorityUtils.getPriority(obj) > PriorityUtils.getPriority(current)) {
                    merged.add(current);
                    if (iter.hasNext()) {
                        current = iter.next();
                    } else {
                        current = null;
                    }
                }
                merged.add(obj);
            }

            if (current != null) {
                merged.add(current);
            }

            while (iter.hasNext()) {
                merged.add(iter.next());
            }

            return merged;
        }

        return extensions;
    }

}
