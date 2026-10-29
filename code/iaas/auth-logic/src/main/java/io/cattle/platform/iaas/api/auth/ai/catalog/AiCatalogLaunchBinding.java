package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.core.model.Stack;
import io.cattle.platform.object.lifecycle.AbstractObjectLifeCycleHandler;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Bind the normal owner-created Stack before its CREATE process can be scheduled. */
public class AiCatalogLaunchBinding extends AbstractObjectLifeCycleHandler {
    private final ThreadLocal<Map<String, Object>> creating = new ThreadLocal<>();
    @Inject AiCatalogLaunchPlanStore store;

    <T> T create(Map<String, Object> plan, Supplier<T> operation) {
        Map<String, Object> previous = creating.get(); creating.set(plan);
        try { return operation.get(); }
        finally { if (previous == null) creating.remove(); else creating.set(previous); }
    }

    @Override protected <T> T onCreate(T instance, Class<?> type, Map<String, Object> properties) {
        Map<String, Object> plan = creating.get();
        if (plan == null || !(instance instanceof Stack stack)) return instance;
        if (!"dispatching".equals(plan.get("status")) || plan.get("internalStackId") != null || stack.getId() == null
                || !Objects.equals(stack.getAccountId(), ((Number) plan.get("accountId")).longValue())
                || !Objects.equals(stack.getName(), plan.get("name")) || !"stack".equals(stack.getKind()))
            throw error(409, "CatalogLaunchSourceChanged");
        plan.put("internalStackId", stack.getId()); store.save(plan);
        return instance;
    }
}
