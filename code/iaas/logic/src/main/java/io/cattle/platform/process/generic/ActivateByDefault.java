package io.cattle.platform.process.generic;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.engine.handler.ProcessPostListener;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.process.common.handler.AbstractObjectProcessLogic;
import io.cattle.platform.util.type.Priority;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.inject.Named;

@Named
public class ActivateByDefault extends AbstractObjectProcessLogic implements ProcessPostListener, Priority {

    private static final Map<String, ConfigProperty<Boolean>> ACTIVATE_BY_TYPE = new ConcurrentHashMap<String, ConfigProperty<Boolean>>();

    protected ConfigProperty<Boolean> activateByDefault(String type) {
        ConfigProperty<Boolean> property = ACTIVATE_BY_TYPE.get(type);
        if (property == null) {
            property = ArchaiusUtil.getBooleanProperty("activate.by.default." + type);
            ConfigProperty<Boolean> existing = ACTIVATE_BY_TYPE.putIfAbsent(type, property);
            if (existing != null) {
                property = existing;
            }
        }
        return property;
    }

    @Override
    public HandlerResult handle(ProcessState state, ProcessInstance process) {
        HandlerResult result = new HandlerResult(true, (Map<Object, Object>) null);

        String type = getObjectManager().getType(state.getResource());
        if (activateByDefault(type).get()) {
            result.shouldDelegate(true);
        } else if (DataAccessor.fieldBool(state.getResource(), "activateOnCreate")) {
            result.shouldDelegate(true);
        }
        return result;
    }

    @Override
    public int getPriority() {
        return Priority.DEFAULT;
    }

    @Override
    public String[] getProcessNames() {
        return new String[] { "*.create" };
    }

}
