package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.core.model.tables.ServiceTable;
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
        boolean supported = STACK_TYPES.contains(resourceType) || ApiKeyTargetResolver.PROJECT_ONLY.contains(type)
                || ApiKeyTargetResolver.PLATFORM.contains(type) || resourceType.equals("project");
        Field<Long> id = table.field("id", Long.class);
        if (!supported || id == null) return DSL.falseCondition();
        Field<Long> account = resourceType.equals("project") ? id
                : STACK_TYPES.contains(resourceType) || ApiKeyTargetResolver.PROJECT_ONLY.contains(type)
                ? table.field("account_id", Long.class) : null;
        // The API calls it stackId; the persisted column remains environment_id.
        // Use the real column so standalone containers and volumes are scoped too.
        Field<Long> stack = resourceType.equals("stack") ? id : table.field("environment_id", Long.class);
        if (resourceType.equals("container")) {
            Field<Long> serviceId = table.field("service_id", Long.class);
            if (serviceId != null) {
                ServiceTable parent = ServiceTable.SERVICE.as("key_scope_parent_service");
                Field<Long> parentStack = DSL.field(DSL.select(parent.STACK_ID).from(parent)
                        .where(parent.ID.eq(serviceId)).and(parent.ACCOUNT_ID.eq(account)));
                stack = stack == null ? parentStack : DSL.coalesce(stack, parentStack);
            }
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
        return grants.and(denials.not());
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
