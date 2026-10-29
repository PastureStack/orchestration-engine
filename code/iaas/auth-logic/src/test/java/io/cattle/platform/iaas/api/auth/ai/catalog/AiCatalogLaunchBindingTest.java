package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.object.lifecycle.ObjectLifeCycleHandler.LifeCycleEvent;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiCatalogLaunchBindingTest {
    @Test public void ownerGeneratedIdIsBoundBeforeSchedulingAndNoScopeSurvivesFailure() {
        AiCatalogLaunchBinding binding = new AiCatalogLaunchBinding(); List<String> events = new ArrayList<>();
        binding.store = new AiCatalogLaunchPlanStore() { @Override public void save(Map<String, Object> plan) {
            assertEquals(889L, plan.get("internalStackId")); events.add("bound");
        } };
        Map<String, Object> plan = new LinkedHashMap<>(Map.of("status", "dispatching", "accountId", 5L, "name", "chosen"));
        StackRecord row = stack(889, 5, "chosen");
        assertSame(row, binding.create(plan, () -> {
            var result = binding.onEvent(LifeCycleEvent.CREATE, row, row.getClass(), Map.of());
            events.add("scheduled"); assertEquals(List.of("bound", "scheduled"), events); return result;
        }));
        assertThrows(IllegalStateException.class, () -> binding.create(new LinkedHashMap<>(), () -> { throw new IllegalStateException("Synthetic failure"); }));
        assertSame(row, binding.onEvent(LifeCycleEvent.CREATE, row, row.getClass(), Map.of())); assertEquals(2, events.size());
        assertFalse(row.getData() != null && row.getData().toString().contains("catalogLaunch"));
    }

    @Test public void foreignOwnerNameKindOrSecondCreateCannotBorrowClaimAndOrdinaryCreationIsUnchanged() {
        AiCatalogLaunchBinding binding = new AiCatalogLaunchBinding();
        binding.store = new AiCatalogLaunchPlanStore() { @Override public void save(Map<String, Object> plan) { fail("No invalid create may bind"); } };
        for (StackRecord row : List.of(stack(889, 99, "chosen"), stack(889, 5, "other"), stack(889, 5, "chosen"))) {
            if (row.getAccountId() == 5 && row.getName().equals("chosen")) row.setKind("composeProject");
            Map<String, Object> plan = new LinkedHashMap<>(Map.of("status", "dispatching", "accountId", 5L, "name", "chosen"));
            assertThrows(ClientVisibleException.class, () -> binding.create(plan, () -> binding.onEvent(LifeCycleEvent.CREATE, row, row.getClass(), Map.of())));
        }
        StackRecord ordinary = stack(990, 5, "ordinary");
        assertSame(ordinary, binding.onEvent(LifeCycleEvent.CREATE, ordinary, ordinary.getClass(), Map.of()));
        Map<String, Object> alreadyBound = new LinkedHashMap<>(Map.of("status", "dispatching", "accountId", 5L, "name", "ordinary", "internalStackId", 989L));
        assertThrows(ClientVisibleException.class, () -> binding.create(alreadyBound,
                () -> binding.onEvent(LifeCycleEvent.CREATE, ordinary, ordinary.getClass(), Map.of())));
    }
    private static StackRecord stack(long id, long project, String name) {
        StackRecord row = new StackRecord(); row.setId(id); row.setAccountId(project); row.setKind("stack"); row.setName(name); return row;
    }
}
