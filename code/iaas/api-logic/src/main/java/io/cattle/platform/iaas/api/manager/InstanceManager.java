package io.cattle.platform.iaas.api.manager;

import io.cattle.platform.api.resource.jooq.AbstractJooqResourceManager;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InstanceManager extends AbstractJooqResourceManager {

    @Override
    public String[] getTypes() {
        return new String[] { "instance", "container", "virtualMachine" };
    }

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[] { Instance.class };
    }

    @Override
    protected Object deleteInternal(String type, String id, Object obj, ApiRequest request) {
        if (!(obj instanceof Instance)) {
            return super.deleteInternal(type, id, obj, request);
        }

        try {
            return super.deleteInternal(type, id, obj, request);
        } catch (ClientVisibleException e) {
            if (ResponseCodes.METHOD_NOT_ALLOWED == e.getStatus() ) {
                scheduleProcess(InstanceConstants.PROCESS_STOP, obj, CollectionUtils.asMap(InstanceConstants.REMOVE_OPTION, true));
                return getObjectManager().reload(obj);
            } else {
                throw e;
            }
        }
    }

    @Override
    protected Object createAndScheduleObject(Class<?> clz, Map<String, Object> properties) {
        Object count = properties.get(InstanceConstants.FIELD_COUNT);

        if (count instanceof Number && ((Number) count).intValue() > 1) {
            int max = ((Number) count).intValue();

            /*
             * Rancher 1.6 multi-create compatibility: the public create path
             * historically returns a List when count > 1. Do not replace this
             * with clz.cast(result), which would break that API behavior.
             */
            List<Object> result = new ArrayList<Object>(max);
            for (int i = 0; i < max; i++) {
                Object instance = super.createAndScheduleObject(clz, properties);
                result.add(instance);
            }

            return result;
        } else {
            return super.createAndScheduleObject(clz, properties);
        }
    }

}
