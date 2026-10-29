package io.cattle.platform.iaas.api.auth.change;

import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.json.JsonMapper;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/** Durable claim and observation, independent from the service-upgrade receipt. */
public class AiResourceChangePlanStore extends AbstractJooqDao {
    static final Table<?> TABLE = DSL.table(DSL.name("ai_resource_change_plan"));
    static final Field<String> ID = DSL.field(DSL.name("id"), String.class);
    static final Field<String> DOCUMENT = DSL.field(DSL.name("document"), String.class);
    static final Field<String> STATE = DSL.field(DSL.name("state"), String.class);
    static final Field<Long> ACTOR = DSL.field(DSL.name("actor_id"), Long.class);
    static final Field<Long> EXPIRY = DSL.field(DSL.name("expires_at"), Long.class);
    @Inject JsonMapper json;

    public void insert(Map<String, Object> plan) {
        create().insertInto(TABLE).set(ID, (String) plan.get("id")).set(STATE, "preview")
                .set(DOCUMENT, encode(plan)).set(ACTOR, ((Number) plan.get("actorId")).longValue())
                .set(EXPIRY, java.time.Instant.parse((String) plan.get("expiresAt")).toEpochMilli()).execute();
    }

    public Map<String, Object> load(String id) {
        String document = create().select(DOCUMENT).from(TABLE).where(ID.eq(id)).fetchOne(DOCUMENT);
        return document == null ? null : decode(document);
    }

    public Map<String, Object> loadForUpdate(String id) {
        create().connection(connection -> {
            if (connection.getAutoCommit()) throw new IllegalStateException("Resource-change write transaction required");
        });
        String document = create().select(DOCUMENT).from(TABLE).where(ID.eq(id)).forUpdate().fetchOne(DOCUMENT);
        return document == null ? null : decode(document);
    }

    public void save(Map<String, Object> plan) {
        create().update(TABLE).set(STATE, (String) plan.get("status")).set(DOCUMENT, encode(plan))
                .where(ID.eq((String) plan.get("id"))).execute();
    }

    public boolean claim(Map<String, Object> plan) {
        return create().update(TABLE).set(STATE, "dispatching").set(DOCUMENT, encode(plan))
                .where(ID.eq((String) plan.get("id")).and(STATE.eq("preview"))).execute() == 1;
    }

    public List<Map<String, Object>> pending(long actor, long now) {
        return create().select(DOCUMENT).from(TABLE).where(ACTOR.eq(actor).and(STATE.eq("preview")).and(EXPIRY.gt(now)))
                .orderBy(EXPIRY.asc(), ID.asc()).limit(20).fetch(DOCUMENT).stream().map(this::decode).toList();
    }

    public int pendingCount(long actor) { return create().fetchCount(TABLE, ACTOR.eq(actor).and(STATE.eq("preview"))); }

    public void prune(long now) {
        create().deleteFrom(TABLE).where(STATE.in("preview", "cancelled").and(EXPIRY.le(now))).execute();
    }

    private String encode(Map<String, Object> plan) {
        try { return json.writeValueAsString(plan); }
        catch (IOException error) { throw new IllegalStateException("Cannot persist resource-change receipt", error); }
    }

    private Map<String, Object> decode(String value) {
        try { return json.readValue(value); }
        catch (IOException error) { throw new IllegalStateException("Resource-change receipt unavailable", error); }
    }
}
