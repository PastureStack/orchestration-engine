package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.core.model.tables.AccountTable;
import io.cattle.platform.core.model.tables.InstanceTable;
import io.cattle.platform.core.model.tables.StackTable;
import io.cattle.platform.core.model.tables.ServiceTable;
import io.cattle.platform.core.model.tables.ServiceExposeMapTable;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiFunction;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/** Row predicate ANDed with existing RBAC before LIMIT or pagination metadata. */
public final class ApiKeyQueryScopes {
    private static final Set<String> STACK_TYPES = Set.of("stack", "service", "container", "volume");
    private ApiKeyQueryScopes() { }

    public static Condition condition(ApiKeyPolicy policy, String type, Table<?> table,
            String operation, BiFunction<String, String, Long> idParser) {
        String resourceType = canonical(type);
        boolean instanceLink = "instanceLink".equals(resourceType);
        boolean supported = instanceLink || STACK_TYPES.contains(resourceType) || ApiKeyTargetResolver.PROJECT_ONLY.contains(type)
                || ApiKeyTargetResolver.PLATFORM.contains(type) || resourceType.equals("project");
        Field<Long> id = table.field("id", Long.class);
        if (!supported || id == null) return DSL.falseCondition();
        Field<Long> account = resourceType.equals("project") ? id
                : instanceLink || STACK_TYPES.contains(resourceType) || ApiKeyTargetResolver.PROJECT_ONLY.contains(type)
                ? table.field("account_id", Long.class) : null;
        // The API calls it stackId; the persisted column remains environment_id.
        // Use the real column so standalone containers and volumes are scoped too.
        Field<Long> stack = resourceType.equals("stack") ? id : table.field("environment_id", Long.class);
        Condition ancestryValid = DSL.trueCondition();
        if (resourceType.equals("container") && policy.getMode() != ApiKeyPolicy.Mode.FULL) {
            Ancestry ancestry = containerAncestry(id, account, stack, table.field("service_id", Long.class));
            stack = ancestry.stack();
            ancestryValid = ancestry.valid();
        }
        if (instanceLink) {
            Ancestry ancestry = instanceLinkAncestry(table, account);
            stack = ancestry.stack();
            ancestryValid = ancestry.valid();
        }
        Condition grants = policy.getDefaultEffect() == ApiKeyPolicy.Effect.ALLOW ? DSL.trueCondition() : DSL.falseCondition();
        Condition denials = DSL.falseCondition();
        for (ApiKeyPolicy.Rule rule : policy.getRules()) {
            if (!rule.operations().contains(operation)) continue;
            ApiKeyPolicy.Scope scope = rule.scope();
            Condition matches = switch (scope.kind()) {
                case GLOBAL -> DSL.trueCondition();
                case PROJECT -> account == null ? DSL.falseCondition() : account.eq(idParser.apply("project", scope.resourceId()));
                case STACK -> stack == null ? DSL.falseCondition() : stack.eq(idParser.apply("stack", scope.resourceId()));
                case RESOURCE -> canonical(scope.resourceType()).equals(resourceType)
                        ? id.eq(idParser.apply(scope.resourceType(), scope.resourceId())) : DSL.falseCondition();
            };
            // A nullable parent is an unmatched scope, not SQL UNKNOWN. Without
            // this, NOT(deny-on-stack) incorrectly hides standalone resources
            // under a default-allow policy.
            matches = DSL.condition(DSL.coalesce(DSL.field(matches), DSL.inline(false)));
            if (rule.effect() == ApiKeyPolicy.Effect.DENY) denials = denials.or(matches);
            else grants = grants.or(matches);
        }
        // Platform resources cannot gain a project scope just because a table
        // happens to carry a personal accountId. The resolver uses the same rule.
        return ancestryValid.and(grants).and(denials.not());
    }

    private record Ancestry(Field<Long> stack, Condition valid) { }

    /** Same persisted source/owner/Stack guard as the direct Link adapter. Reuse
     * container ancestry, including managed and exact upgrade maps; the Link's
     * target_instance_id is not its parent and remains an input-reference check.
     */
    private static Ancestry instanceLinkAncestry(Table<?> table, Field<Long> account) {
        Field<Long> instanceId = table.field("instance_id", Long.class);
        if (account == null || instanceId == null) return new Ancestry(DSL.inline((Long) null), DSL.falseCondition());
        InstanceTable source = InstanceTable.INSTANCE.as("key_scope_link_instance");
        Ancestry parents = containerAncestry(source.ID, source.ACCOUNT_ID, source.STACK_ID, source.SERVICE_ID);
        AccountTable project = AccountTable.ACCOUNT.as("key_scope_link_project");
        StackTable parentStack = StackTable.STACK.as("key_scope_link_stack");
        Condition projectValid = DSL.exists(DSL.selectOne().from(project).where(project.ID.eq(account))
                .and(project.KIND.eq("project")).and(live(project.REMOVED, project.STATE)));
        // The Stack ID is a primary key, so this lookup returns at most one
        // marker. A missing/foreign/removed parent yields NULL, not a grant.
        // Unlike correlated EXISTS, MariaDB cannot rewrite this scalar marker
        // into a row-valued IN whose left side contains ancestry subqueries.
        Condition stackValid = parents.stack().isNull().or(DSL.field(DSL.selectOne().from(parentStack)
                .where(parentStack.ID.eq(parents.stack())).and(parentStack.ACCOUNT_ID.eq(account))
                .and(live(parentStack.REMOVED, parentStack.STATE))).eq(DSL.inline(1)));
        Condition sourceValid = source.ID.eq(instanceId).and(source.ACCOUNT_ID.eq(account))
                .and(live(source.REMOVED, source.STATE)).and(parents.valid()).and(stackValid);
        Field<Long> stack = DSL.field(DSL.select(parents.stack()).from(source).where(sourceValid));
        Condition valid = live(table.field("removed"), table.field("state", String.class)).and(projectValid)
                .and(DSL.exists(DSL.selectOne().from(source).where(sourceValid)));
        return new Ancestry(stack, valid);
    }

    private static Condition live(Field<?> removed, Field<String> state) {
        if (removed == null || state == null) return DSL.falseCondition();
        return removed.isNull().and(state.isNull().or(state.notIn("removed", "purging", "purged")));
    }

    /** Match the direct resolver's persisted managed or upgrade Service ancestry. The
     * scalar MIN is not a grant: every parent must agree with that result before
     * any row can match, including default-allow and resource-specific rules.
     */
    private static Ancestry containerAncestry(Field<Long> id, Field<Long> account,
            Field<Long> nativeStack, Field<Long> serviceId) {
        if (account == null) return new Ancestry(nativeStack, DSL.falseCondition());
        ServiceTable direct = ServiceTable.SERVICE.as("key_scope_parent_service");
        Field<Long> directStack = serviceId == null ? DSL.inline((Long) null)
                : DSL.field(DSL.select(direct.STACK_ID).from(direct).where(direct.ID.eq(serviceId))
                        .and(direct.ACCOUNT_ID.eq(account)).and(direct.REMOVED.isNull()));
        ServiceExposeMapTable mapped = ServiceExposeMapTable.SERVICE_EXPOSE_MAP.as("key_scope_managed_map");
        ServiceTable managed = ServiceTable.SERVICE.as("key_scope_managed_service");
        Field<Long> managedStack = DSL.field(DSL.select(DSL.min(managed.STACK_ID)).from(mapped)
                .join(managed).on(managed.ID.eq(mapped.SERVICE_ID))
                .where(mapped.INSTANCE_ID.eq(id)).and(mapped.REMOVED.isNull()).and(ownedServiceMap(mapped))
                .and(mapped.ACCOUNT_ID.eq(account)).and(managed.ACCOUNT_ID.eq(account)).and(managed.REMOVED.isNull()));
        Field<Long> stack = nativeStack == null ? DSL.coalesce(directStack, managedStack)
                : DSL.coalesce(nativeStack, directStack, managedStack);
        // A non-null hint must resolve to a live, same-account Service and agree
        // even when one side's Stack is NULL. NULL is not a wildcard parent.
        Condition directValid = serviceId == null ? DSL.trueCondition() : serviceId.isNull().or(DSL.exists(
                DSL.selectOne().from(direct).where(direct.ID.eq(serviceId)).and(direct.ACCOUNT_ID.eq(account))
                        .and(direct.REMOVED.isNull()).and(sameNullableStack(direct.STACK_ID, stack))));
        ServiceExposeMapTable checkedMap = ServiceExposeMapTable.SERVICE_EXPOSE_MAP.as("key_scope_checked_map");
        ServiceTable checkedService = ServiceTable.SERVICE.as("key_scope_checked_service");
        // Inspect all owned references, not just the valid ones used for MIN:
        // excluding a foreign/dangling map from that aggregate must not hide it.
        Condition invalidParent = checkedMap.ACCOUNT_ID.isDistinctFrom(account).or(checkedService.ID.isNull())
                .or(checkedService.REMOVED.isNotNull()).or(checkedService.ACCOUNT_ID.isDistinctFrom(account))
                .or(sameNullableStack(checkedService.STACK_ID, stack).not());
        Condition managedValid = DSL.notExists(DSL.selectOne().from(checkedMap).leftJoin(checkedService)
                .on(checkedService.ID.eq(checkedMap.SERVICE_ID)).where(checkedMap.INSTANCE_ID.eq(id))
                .and(checkedMap.REMOVED.isNull()).and(ownedServiceMap(checkedMap)).and(invalidParent));
        return new Ancestry(stack, directValid.and(managedValid));
    }

    /** Total equality, including NULL/NULL, without DISTINCT FROM emulation.
     * MariaDB rejects a scalar subquery in the row/IN shape used by that
     * emulation. Guard ordinary equality with both non-null checks so negation
     * cannot become UNKNOWN and omit an incoherent managed parent.
     */
    static Condition sameNullableStack(Field<Long> left, Field<Long> right) {
        return left.isNull().and(right.isNull())
                .or(left.isNotNull().and(right.isNotNull()).and(left.eq(right)));
    }

    private static Condition ownedServiceMap(ServiceExposeMapTable map) {
        return map.MANAGED.eq(true).or(map.MANAGED.eq(false).and(map.UPGRADE.eq(true)));
    }

    public static String canonical(String type) {
        if (type == null) return "unknown";
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "instance", "container" -> "container";
            case "environment", "stack" -> "stack";
            case "apikey", "apikeyrestricted", "credential" -> "apiKey";
            default -> type;
        };
    }
}
