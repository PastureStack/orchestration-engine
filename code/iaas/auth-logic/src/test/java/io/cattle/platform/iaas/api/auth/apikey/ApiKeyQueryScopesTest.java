package io.cattle.platform.iaas.api.auth.apikey;
import static org.junit.Assert.*;
import io.cattle.platform.core.model.tables.InstanceTable;
import io.cattle.platform.core.model.tables.StackTable;
import io.cattle.platform.core.model.tables.AccountTable;
import io.cattle.platform.core.model.tables.ServiceTable;
import io.cattle.platform.core.model.tables.VolumeTable;
import java.util.List;
import java.util.Set;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.Test;

public class ApiKeyQueryScopesTest {
    private ApiKeyPolicy.Rule rule(String id, ApiKeyPolicy.Effect effect, ApiKeyPolicy.Scope scope) {
        return new ApiKeyPolicy.Rule(id, effect, scope, Set.of("read"));
    }
    private String sql(String type, org.jooq.Table<?> table, ApiKeyPolicy.Effect base, ApiKeyPolicy.Rule... rules) {
        return sql(type, table, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, base, null, List.of(rules)));
    }
    private String sql(String type, org.jooq.Table<?> table, ApiKeyPolicy policy) {
        var condition = ApiKeyQueryScopes.condition(policy, type, table, "read", (kind, id) -> Long.valueOf(id.replaceAll("^[^0-9]*[0-9]+[A-Za-z]+", "")));
        return DSL.using(SQLDialect.MYSQL, new org.jooq.conf.Settings().withRenderSchema(false))
                .renderInlined(DSL.selectFrom(table).where(condition).limit(2)).toLowerCase();
    }
    @Test public void scopePredicatePrecedesPaginationAndDenyIsConjunctive() {
        String sql = sql("instance", InstanceTable.INSTANCE, ApiKeyPolicy.Effect.ALLOW,
                rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.stack("1st9")));
        assertTrue(sql.contains("where")); assertTrue(sql.indexOf("where") < sql.indexOf("limit"));
        assertTrue(sql, sql.contains("not")); assertTrue(sql, sql.contains("environment_id")); assertTrue(sql, sql.contains("key_scope_parent_service"));
        assertTrue(sql, sql.contains("coalesce(`instance`.`environment_id`"));
    }
    @Test public void stackIdentityUsesPersistedIdNotName() {
        String sql = sql("stack", StackTable.STACK, ApiKeyPolicy.Effect.DENY,
                rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8")));
        assertTrue(sql, sql.contains("`environment`.`id` = 8")); assertFalse(sql.contains("name ="));
    }
    @Test public void platformAccountCannotBorrowProjectAccountScope() {
        String sql = sql("account", AccountTable.ACCOUNT, ApiKeyPolicy.Effect.DENY,
                rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5")));
        assertTrue(sql.contains("false"));
    }
    @Test public void unknownTableCannotBeDefaultAllowed() {
        assertTrue(sql("futurePlugin", InstanceTable.INSTANCE, ApiKeyPolicy.Effect.ALLOW).contains("false"));
    }
    @Test public void canonicalResourceAliasesCannotEscapeDeny() {
        assertEquals("container", ApiKeyQueryScopes.canonical("instance"));
        assertEquals("apiKey", ApiKeyQueryScopes.canonical("apiKeyRestricted"));
        String sql = sql("instance", InstanceTable.INSTANCE, ApiKeyPolicy.Effect.ALLOW,
                rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("container", "1i3")));
        assertTrue(sql.contains("`instance`.`id` = 3"));
    }
    @Test public void serviceAndVolumeUseActualPersistedStackColumn() {
        for (var entry : java.util.Map.<String, org.jooq.Table<?>>of("service", ServiceTable.SERVICE, "volume", VolumeTable.VOLUME).entrySet()) {
            String sql = sql(entry.getKey(), entry.getValue(), ApiKeyPolicy.Effect.DENY,
                    rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8")));
            assertTrue(sql, sql.contains("`" + entry.getKey() + "`.`environment_id` = 8"));
        }
    }
    @Test public void nullableParentDenyIsUnmatchedRatherThanSqlUnknown() {
        String sql = sql("volume", VolumeTable.VOLUME, ApiKeyPolicy.Effect.ALLOW,
                rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.stack("1st8")));
        assertTrue(sql, sql.contains("coalesce((`volume`.`environment_id` = 8), false)")
                || sql.contains("coalesce(`volume`.`environment_id` = 8, false)"));
    }
    @Test public void managedServiceMapSuppliesStackWhenDirectParentsAreNull() {
        String sql = sql("container", InstanceTable.INSTANCE, ApiKeyPolicy.Effect.DENY,
                rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8")));
        assertTrue(sql, sql.contains("min(`key_scope_managed_service`.`environment_id`)"));
        assertTrue(sql, sql.contains("`key_scope_managed_map`.`instance_id` = `instance`.`id`"));
        assertTrue(sql, sql.contains("coalesce(`instance`.`environment_id`"));
        assertTrue(sql, sql.contains("`key_scope_parent_service`.`id` = `instance`.`service_id`"));
        assertTrue(sql, sql.contains("= 8"));
    }
    @Test public void managedStackAggregateRequiresLiveManagedSameAccountReferences() {
        String sql = sql("container", InstanceTable.INSTANCE, ApiKeyPolicy.Effect.DENY,
                rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8")));
        assertTrue(sql, sql.contains("`key_scope_managed_map`.`removed` is null"));
        assertTrue(sql, sql.contains("`key_scope_managed_map`.`managed` = true"));
        assertTrue(sql, sql.contains("`key_scope_managed_map`.`account_id` = `instance`.`account_id`"));
        assertTrue(sql, sql.contains("`key_scope_managed_service`.`account_id` = `instance`.`account_id`"));
        assertTrue(sql, sql.contains("`key_scope_managed_service`.`removed` is null"));
        assertTrue(sql, sql.contains("`key_scope_managed_service`.`id` = `key_scope_managed_map`.`service_id`"));
    }
    @Test public void persistedUpgradeMapHasSameOwnershipPredicateInAggregateAndCoherenceGuard() {
        for (ApiKeyPolicy.Effect base : ApiKeyPolicy.Effect.values()) {
            String sql = sql("container", InstanceTable.INSTANCE, base,
                    rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8")),
                    rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("container", "1i3")));
            for (String alias : List.of("key_scope_managed_map", "key_scope_checked_map")) {
                String field = "`" + alias + "`";
                assertTrue(sql, sql.contains("(" + field + ".`managed` = true or (" + field + ".`managed` = false and " + field + ".`upgrade` = true))"));
                assertTrue(sql, sql.contains(field + ".`removed` is null"));
            }
            assertTrue(sql, sql.contains("`key_scope_managed_map`.`account_id` = `instance`.`account_id`"));
            assertTrue(sql, sql.contains("`key_scope_managed_service`.`removed` is null"));
            assertTrue(sql, sql.contains("not(`key_scope_checked_map`.`account_id` <=>"));
            assertTrue(sql, sql.contains("`key_scope_checked_service`.`id` is null"));
            assertTrue(sql, sql.contains("not(`key_scope_checked_service`.`environment_id` <=> coalesce(`instance`.`environment_id`"));
            assertTrue(sql, sql.indexOf("not exists") < sql.indexOf("`instance`.`id` = 3"));
            assertTrue(sql, sql.contains("and not (false or coalesce"));
            assertTrue(sql, sql.indexOf("`instance`.`id` = 3") < sql.indexOf("limit"));
        }
    }
    @Test public void foreignOrDanglingManagedMapInvalidatesWholeRowRatherThanBeingFilteredOut() {
        String sql = sql("container", InstanceTable.INSTANCE, ApiKeyPolicy.Effect.ALLOW);
        assertTrue(sql, sql.contains("not exists"));
        assertTrue(sql, sql.contains("left outer join `service` as `key_scope_checked_service`"));
        assertTrue(sql, sql.contains("`key_scope_checked_map`.`instance_id` = `instance`.`id`"));
        assertTrue(sql, sql.contains("`key_scope_checked_map`.`removed` is null"));
        assertTrue(sql, sql.contains("`key_scope_checked_map`.`managed` = true"));
        assertTrue(sql, sql.contains("`key_scope_checked_map`.`account_id` <=> `instance`.`account_id`"));
        assertTrue(sql, sql.contains("`key_scope_checked_service`.`id` is null"));
        assertTrue(sql, sql.contains("`key_scope_checked_service`.`removed` is not null"));
        assertTrue(sql, sql.contains("`key_scope_checked_service`.`account_id` <=> `instance`.`account_id`"));
        // Foreign/null account references are rejected, not merely excluded
        // from the valid aggregate and then mistaken for a standalone row.
        assertTrue(sql, sql.contains("not(`key_scope_checked_map`.`account_id` <=>"));
        assertTrue(sql, sql.contains("not(`key_scope_checked_service`.`account_id` <=>"));
    }
    @Test public void directServiceHintMustResolveLiveSameAccountAndAgreeNullSafely() {
        String sql = sql("container", InstanceTable.INSTANCE, ApiKeyPolicy.Effect.ALLOW);
        assertTrue(sql, sql.contains("`instance`.`service_id` is null or exists"));
        assertTrue(sql, sql.contains("`key_scope_parent_service`.`removed` is null"));
        assertTrue(sql, sql.contains("`key_scope_parent_service`.`account_id` = `instance`.`account_id`"));
        assertTrue(sql, sql.contains("`key_scope_parent_service`.`environment_id` <=> coalesce(`instance`.`environment_id`"));
    }
    @Test public void everyManagedParentMustAgreeIncludingNullVersusNonNullStacks() {
        String sql = sql("container", InstanceTable.INSTANCE, ApiKeyPolicy.Effect.DENY,
                rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8")));
        // MIN chooses a scalar only after the NOT EXISTS coherence guard has
        // checked every map; neither another Stack nor a NULL Stack can hide.
        assertTrue(sql, sql.contains("not(`key_scope_checked_service`.`environment_id` <=> coalesce(`instance`.`environment_id`"));
        assertFalse(sql, sql.contains("`key_scope_checked_service`.`environment_id` <>"));
        assertTrue(sql, sql.indexOf("not exists") < sql.lastIndexOf("= 8"));
    }
    @Test public void standaloneDefaultAllowStillTreatsNullableStackDenyAsUnmatched() {
        String sql = sql("container", InstanceTable.INSTANCE, ApiKeyPolicy.Effect.ALLOW,
                rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.stack("1st8")));
        assertTrue(sql, sql.contains("`instance`.`service_id` is null or exists"));
        assertTrue(sql, sql.contains("not exists"));
        assertTrue(sql, sql.contains("not (false or coalesce((coalesce(`instance`.`environment_id`"));
        assertTrue(sql, sql.contains("= 8), false)"));
    }
    @Test public void resourceGrantCannotBypassParentCoherenceOrStackDeny() {
        String sql = sql("container", InstanceTable.INSTANCE, ApiKeyPolicy.Effect.DENY,
                rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("container", "1i3")),
                rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.stack("1st8")));
        assertTrue(sql, sql.indexOf("not exists") < sql.indexOf("`instance`.`id` = 3"));
        assertTrue(sql, sql.contains("and (false or coalesce"));
        assertTrue(sql, sql.contains("and not (false or coalesce"));
        assertTrue(sql, sql.lastIndexOf("= 8") < sql.indexOf("limit"));
    }
    @Test public void fullModeKeepsOriginalUnrestrictedRowPredicate() {
        String sql = sql("container", InstanceTable.INSTANCE,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
        assertFalse(sql, sql.contains("service_expose_map"));
        assertFalse(sql, sql.contains("key_scope_parent_service"));
        String predicate = sql.substring(sql.indexOf("where"));
        assertFalse(sql, predicate.contains("environment_id"));
        assertTrue(sql, predicate.equals("where (true and true and not false) limit 2"));
    }
}
