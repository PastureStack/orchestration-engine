package io.cattle.platform.extension.dynamic.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import io.cattle.platform.engine.handler.ProcessHandler;
import io.cattle.platform.engine.handler.ProcessPreListener;
import io.cattle.platform.extension.dynamic.dao.ExternalHandlerDao;
import io.cattle.platform.extension.dynamic.data.ExternalHandlerData;
import io.cattle.platform.process.common.handler.EventBasedProcessHandler;

public class ExternalDynamicExtensionHandlerImplTest {

    @Test
    public void returnsProcessHandlersForHandlerKey() {
        ExternalDynamicExtensionHandlerImpl handler = handlerWithData("instance.create", handlerData("external-one", 17));

        List<?> values = handler.getExtensionList("process.instance.create.handlers", ProcessHandler.class);

        assertEquals(1, values.size());
        assertTrue(values.get(0) instanceof EventBasedProcessHandler);

        EventBasedProcessHandler processHandler = EventBasedProcessHandler.class.cast(values.get(0));
        assertEquals("external-one", processHandler.getName());
        assertEquals(17, processHandler.getPriority());
        assertEquals("instance.create;handler=external-one", processHandler.getEventName());
    }

    @Test
    public void preservesLegacyProcessLogicOnlyTypeGateForPreListenerKeys() {
        ExternalDynamicExtensionHandlerImpl handler = handlerWithData("pre.instance.create", handlerData("external-pre", 5));

        List<?> values = handler.getExtensionList("process.instance.create.pre.listeners", ProcessPreListener.class);

        assertEquals(1, values.size());
        assertTrue(values.get(0) instanceof EventBasedProcessHandler);
        assertEquals("pre.instance.create;handler=external-pre",
                EventBasedProcessHandler.class.cast(values.get(0)).getEventName());
    }

    @Test
    public void rejectsNonProcessLogicTypesAndNonProcessKeys() {
        ExternalDynamicExtensionHandlerImpl handler = handlerWithData("instance.create", handlerData("external-one", 17));

        assertTrue(handler.getExtensionList("process.instance.create.handlers", String.class).isEmpty());
        assertTrue(handler.getExtensionList("not.a.process.key", ProcessHandler.class).isEmpty());
    }

    private ExternalDynamicExtensionHandlerImpl handlerWithData(final String expectedProcessName, ExternalHandlerData... data) {
        ExternalDynamicExtensionHandlerImpl handler = new ExternalDynamicExtensionHandlerImpl();
        final List<ExternalHandlerData> handlers = Arrays.asList(data);
        handler.setExternalHandlerDao(new ExternalHandlerDao() {
            @Override
            public List<? extends ExternalHandlerData> getExternalHandlerData(String processName) {
                if (expectedProcessName.equals(processName)) {
                    return handlers;
                }
                return Collections.emptyList();
            }
        });
        return handler;
    }

    private ExternalHandlerData handlerData(String name, int priority) {
        ExternalHandlerData data = new ExternalHandlerData();
        data.setName(name);
        data.setPriority(priority);
        return data;
    }
}
