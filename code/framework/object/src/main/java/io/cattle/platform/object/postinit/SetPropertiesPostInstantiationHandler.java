package io.cattle.platform.object.postinit;

import java.lang.reflect.InvocationTargetException;
import java.util.Map;

import org.apache.commons.beanutils2.BeanUtils;

public class SetPropertiesPostInstantiationHandler implements ObjectPostInstantiationHandler {

    @Override
    public <T> T postProcess(T obj, Class<?> clz, Map<String, Object> properties) {
        try {
            BeanUtils.copyProperties(obj, properties);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        } catch (InvocationTargetException e) {
            throw new IllegalStateException(e);
        }

        return obj;
    }

}
