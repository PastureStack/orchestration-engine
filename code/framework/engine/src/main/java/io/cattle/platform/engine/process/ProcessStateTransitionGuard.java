package io.cattle.platform.engine.process;

import java.util.function.Supplier;

/** Optional resource-domain boundary around the actual state write, including scheduling. */
public interface ProcessStateTransitionGuard {
    boolean transition(Object resource, String stateField, String newState, Supplier<Boolean> next);
}
