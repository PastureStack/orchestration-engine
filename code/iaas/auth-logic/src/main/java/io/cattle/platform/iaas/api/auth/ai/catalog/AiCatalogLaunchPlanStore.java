package io.cattle.platform.iaas.api.auth.ai.catalog;

import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.json.JsonMapper;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One launch claim and a serialized exact-graph request ledger, independent of upgrade/resource-change plans. */
public class AiCatalogLaunchPlanStore extends AbstractJooqDao {
    static final Table<?> TABLE = DSL.table(DSL.name("ai_catalog_stack_launch_plan"));
    static final Field<String> ID = DSL.field(DSL.name("id"), String.class);
    static final Field<String> STATE = DSL.field(DSL.name("state"), String.class);
    static final Field<String> DOCUMENT = DSL.field(DSL.name("document"), String.class);
    static final Field<Long> ACTOR = DSL.field(DSL.name("actor_id"), Long.class);
    static final Field<Long> PROJECT = DSL.field(DSL.name("account_id"), Long.class);
    static final Field<Long> STACK = DSL.field(DSL.name("stack_id"), Long.class);
    static final Field<Long> EXPIRY = DSL.field(DSL.name("expires_at"), Long.class);
    @Inject JsonMapper json;
    @Inject @Named("CoreTransactionManager") PlatformTransactionManager transactions;

    public <T> T transaction(java.util.function.Supplier<T> operation) {
        return new TransactionTemplate(transactions).execute(status -> operation.get());
    }

    /** Authentication/event hooks need a durable claim independent of a later API transaction. */
    public <T> T independent(java.util.function.Supplier<T> operation) {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> operation.get());
    }

    public void insert(Map<String, Object> plan) {
        create().insertInto(TABLE).set(ID, (String) plan.get("id")).set(STATE, "needs_user").set(DOCUMENT, encode(plan))
                .set(ACTOR, ((Number) plan.get("actorId")).longValue()).set(PROJECT, ((Number) plan.get("accountId")).longValue())
                .set(EXPIRY, java.time.Instant.parse((String) plan.get("expiresAt")).toEpochMilli()).execute();
    }

    public Map<String, Object> load(String id) {
        String document = create().select(DOCUMENT).from(TABLE).where(ID.eq(id)).fetchOne(DOCUMENT);
        return document == null ? null : decode(document);
    }

    public Map<String, Object> loadForUpdate(String id) {
        create().connection(connection -> {
            if (connection.getAutoCommit()) throw new IllegalStateException("Catalog launch write transaction required");
        });
        String document = create().select(DOCUMENT).from(TABLE).where(ID.eq(id)).forUpdate().fetchOne(DOCUMENT);
        return document == null ? null : decode(document);
    }

    public Map<String, Object> forStack(long stackId) {
        String document = create().select(DOCUMENT).from(TABLE).where(STACK.eq(stackId)).fetchOne(DOCUMENT);
        return document == null ? null : decode(document);
    }

    public void save(Map<String, Object> plan) {
        create().update(TABLE).set(STATE, (String) plan.get("status")).set(DOCUMENT, encode(plan))
                .set(STACK, plan.get("internalStackId") instanceof Number stack ? stack.longValue() : null).where(ID.eq((String) plan.get("id"))).execute();
    }

    public boolean claim(Map<String, Object> plan) {
        return create().update(TABLE).set(STATE, "dispatching").set(DOCUMENT, encode(plan))
                .where(ID.eq((String) plan.get("id")).and(STATE.eq("needs_user"))).execute() == 1;
    }

    public List<Map<String, Object>> pending(long actor, long project, long now) {
        return create().select(DOCUMENT).from(TABLE).where(ACTOR.eq(actor).and(PROJECT.eq(project))
                .and(STATE.eq("needs_user")).and(EXPIRY.gt(now))).orderBy(EXPIRY.asc(), ID.asc()).limit(20)
                .fetch(DOCUMENT).stream().map(this::decode).toList();
    }

    public int pendingCount(long actor, long now) {
        return create().fetchCount(TABLE, ACTOR.eq(actor).and(STATE.eq("needs_user")).and(EXPIRY.gt(now)));
    }

    private String encode(Map<String, Object> plan) {
        try { return json.writeValueAsString(plan); }
        catch (IOException failure) { throw new IllegalStateException("Catalog launch receipt unavailable"); }
    }
    private Map<String, Object> decode(String document) {
        try { return json.readValue(document); }
        catch (IOException failure) { throw new IllegalStateException("Catalog launch receipt unavailable"); }
    }
}
