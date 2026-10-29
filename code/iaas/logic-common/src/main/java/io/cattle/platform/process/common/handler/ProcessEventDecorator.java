package io.cattle.platform.process.common.handler;

import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.eventing.model.EventVO;

/** Server-owned event augmentation, before the existing external process dispatch. */
public interface ProcessEventDecorator {
    void decorate(ProcessState state, ProcessInstance process, EventVO<Object> event);
}
