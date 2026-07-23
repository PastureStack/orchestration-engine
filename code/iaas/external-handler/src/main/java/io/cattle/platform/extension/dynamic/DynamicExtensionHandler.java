package io.cattle.platform.extension.dynamic;

import java.util.List;

public interface DynamicExtensionHandler {

    List<?> getExtensionList(String key, Class<?> type);

}
