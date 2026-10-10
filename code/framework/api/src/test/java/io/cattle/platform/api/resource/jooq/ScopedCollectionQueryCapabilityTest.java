package io.cattle.platform.api.resource.jooq;

import static org.junit.Assert.*;
import io.cattle.platform.api.auth.ApiResourceAccess;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Pagination;
import java.util.List;
import java.util.Map;
import org.jooq.Condition;
import org.jooq.SelectQuery;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.Test;

public class ScopedCollectionQueryCapabilityTest {
    private final ApiResourceAccess guard = (request, factory, type, table) -> DSL.falseCondition();
    private <T extends AbstractJooqResourceManager> T bind(T manager) {
        manager.resourceAccess = List.of(guard);
        return manager;
    }

    @Test public void unchangedQueryRequiresTheSameActuallyInjectedGuard() {
        var manager = new DefaultJooqResourceManager();
        assertFalse(manager.supportsScopedCollectionQuery(guard));
        bind(manager);
        assertTrue(manager.supportsScopedCollectionQuery(guard));
        assertFalse(manager.supportsScopedCollectionQuery(null));
        assertFalse(manager.supportsScopedCollectionQuery(new Object()));
    }
    @Test public void fourArgumentListOverrideFailsClosedEvenWhenItDelegates() {
        var manager = bind(new DefaultJooqResourceManager() {
            @Override protected Object listInternal(SchemaFactory factory, String type, Map<Object, Object> criteria, ListOptions options) {
                return super.listInternal(factory, type, criteria, options);
            }
        });
        assertFalse(manager.supportsScopedCollectionQuery(guard));
    }
    @Test public void fiveArgumentQueryOverrideFailsClosedEvenWhenItDelegates() {
        var manager = bind(new DefaultJooqResourceManager() {
            @Override protected Object listInternal(SchemaFactory factory, String type, Map<Object, Object> criteria,
                    ListOptions options, Map<Table<?>, Condition> joins) {
                return super.listInternal(factory, type, criteria, options, joins);
            }
        });
        assertFalse(manager.supportsScopedCollectionQuery(guard));
    }
    @Test public void paginationOverrideCannotClaimTheGuardedQueryContract() {
        var manager = bind(new DefaultJooqResourceManager() {
            @Override protected void addLimit(SchemaFactory factory, String type, Pagination pagination, SelectQuery<?> query) {
                super.addLimit(factory, type, pagination, query);
            }
        });
        assertFalse(manager.supportsScopedCollectionQuery(guard));
    }
    @Test public void paginationOutputOverrideCannotClaimTheGuardedQueryContract() {
        var manager = bind(new DefaultJooqResourceManager() {
            @Override protected void processPaginationResult(List<?> values, Pagination pagination, MultiTableMapper mapper) {
                super.processPaginationResult(values, pagination, mapper);
            }
        });
        assertFalse(manager.supportsScopedCollectionQuery(guard));
    }
}
