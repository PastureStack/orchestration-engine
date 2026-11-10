package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.assertEquals;
import static io.cattle.platform.core.model.tables.StoragePoolTable.STORAGE_POOL;

import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.Test;

/** Execute the actual predicate against mixed native kinds in the shared table. */
public class ApiKeyRegistryQueryScopeTest {
    private ApiKeyPolicy.Rule rule(String name, ApiKeyPolicy.Effect effect, String type, String id) {
        return new ApiKeyPolicy.Rule(name, effect, ApiKeyPolicy.Scope.resource(type, id), Set.of("read"));
    }

    private List<Long> visible(ApiKeyPolicy.Effect base, ApiKeyPolicy.Rule... rules) throws Exception {
        return visibleAs("storagePool", base, rules);
    }

    private List<Long> visibleAs(String type, ApiKeyPolicy.Effect base, ApiKeyPolicy.Rule... rules) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            var db = DSL.using(connection, SQLDialect.H2, new Settings().withRenderSchema(false));
            db.execute("CREATE TABLE storage_pool (id BIGINT PRIMARY KEY, account_id BIGINT, kind VARCHAR(128))");
            db.execute("INSERT INTO storage_pool VALUES (71,5,'registry'),(72,5,'registry'),(73,5,'docker'),(74,6,'registry')");
            var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, base, null, List.of(rules));
            // ObjectManager supplies its persisted core type, not the derived API schema.
            var predicate = ApiKeyQueryScopes.condition(policy, type, STORAGE_POOL, "read",
                    (scopeType, id) -> Long.valueOf(id.replaceFirst("^1sp", "")));
            return db.select(STORAGE_POOL.ID).from(STORAGE_POOL).where(STORAGE_POOL.ACCOUNT_ID.eq(5L))
                    .and(predicate).orderBy(STORAGE_POOL.ID).fetch(STORAGE_POOL.ID);
        }
    }

    @Test public void exactRegistryGrantSurvivesItsNativeParentTableLookup() throws Exception {
        assertEquals(List.of(71L), visible(ApiKeyPolicy.Effect.DENY,
                rule("allow-one", ApiKeyPolicy.Effect.ALLOW, "registry", "1sp71")));
    }

    @Test public void registryDenyCannotBeBypassedThroughTheStoragePoolParentQuery() throws Exception {
        assertEquals(List.of(72L, 73L), visible(ApiKeyPolicy.Effect.ALLOW,
                rule("deny-one", ApiKeyPolicy.Effect.DENY, "registry", "1sp71")));
    }

    @Test public void denyStillWinsOverAnExplicitParentIdentityGrant() throws Exception {
        assertEquals(List.of(), visible(ApiKeyPolicy.Effect.DENY,
                rule("allow-parent", ApiKeyPolicy.Effect.ALLOW, "storagePool", "1sp71"),
                rule("deny-registry", ApiKeyPolicy.Effect.DENY, "registry", "1sp71")));
        assertEquals(List.of(71L), visibleAs("registry", ApiKeyPolicy.Effect.DENY,
                rule("allow-parent", ApiKeyPolicy.Effect.ALLOW, "storagePool", "1sp71")));
        assertEquals(List.of(), visibleAs("registry", ApiKeyPolicy.Effect.DENY,
                rule("allow-registry", ApiKeyPolicy.Effect.ALLOW, "registry", "1sp71"),
                rule("deny-parent", ApiKeyPolicy.Effect.DENY, "storagePool", "1sp71")));
        assertEquals(List.of(), visibleAs("registry", ApiKeyPolicy.Effect.DENY,
                rule("wrong-kind-parent", ApiKeyPolicy.Effect.ALLOW, "storagePool", "1sp73")));
    }

    @Test public void aRegistryRuleCannotGrantAnOrdinaryPoolWithTheSameSuppliedId() throws Exception {
        assertEquals(List.of(), visible(ApiKeyPolicy.Effect.DENY,
                rule("wrong-kind", ApiKeyPolicy.Effect.ALLOW, "registry", "1sp73")));
    }

    @Test public void nativeOwnerRestrictionRemainsConjunctiveForForeignRegistryId() throws Exception {
        assertEquals(List.of(), visible(ApiKeyPolicy.Effect.DENY,
                rule("foreign", ApiKeyPolicy.Effect.ALLOW, "registry", "1sp74")));
    }

    @Test public void ordinaryPoolScopesAndDefaultAllowKeepTheirExistingRows() throws Exception {
        assertEquals(List.of(73L), visible(ApiKeyPolicy.Effect.DENY,
                rule("ordinary-pool", ApiKeyPolicy.Effect.ALLOW, "storagePool", "1sp73")));
        assertEquals(List.of(71L,72L,73L), visible(ApiKeyPolicy.Effect.ALLOW));
    }
}
