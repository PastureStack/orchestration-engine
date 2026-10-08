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
        var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, base, null, List.of(rules));
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
}
