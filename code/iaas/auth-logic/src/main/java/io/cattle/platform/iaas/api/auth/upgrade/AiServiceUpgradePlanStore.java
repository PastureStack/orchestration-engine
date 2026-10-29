package io.cattle.platform.iaas.api.auth.upgrade;

import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.json.JsonMapper;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Map;
import java.util.List;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;

/** A dedicated durable receipt, deliberately separate from settings and service data. */
public class AiServiceUpgradePlanStore extends AbstractJooqDao {
    static final Table<?> TABLE = DSL.table(DSL.name("ai_service_upgrade_plan"));
    static final Field<String> ID = DSL.field(DSL.name("id"), String.class);
    static final Field<String> DOCUMENT = DSL.field(DSL.name("document"), String.class);
    static final Field<String> STATE = DSL.field(DSL.name("state"), String.class);
    static final Field<Long> ACTOR = DSL.field(DSL.name("actor_id"), Long.class);
    static final Field<Long> EXPIRY = DSL.field(DSL.name("expires_at"), Long.class);
    @Inject JsonMapper json;

    public void insert(Map<String, Object> plan) {
        create().insertInto(TABLE).set(ID, (String) plan.get("id"))
                .set(STATE, "preview").set(DOCUMENT, encode(plan))
                .set(ACTOR, ((Number) plan.get("actorId")).longValue())
                .set(EXPIRY, java.time.Instant.parse((String) plan.get("expiresAt")).toEpochMilli()).execute();
    }

    public void pruneExpiredPreviews(long now) {
        create().deleteFrom(TABLE).where(STATE.in("preview", "cancelled").and(EXPIRY.le(now))).execute();
    }

    public int pendingCount(long actor) {
        return create().fetchCount(TABLE, ACTOR.eq(actor).and(STATE.eq("preview")));
    }

    public List<Map<String, Object>> pending(long actor, long now) {
        return create().select(DOCUMENT).from(TABLE)
                .where(ACTOR.eq(actor).and(STATE.eq("preview")).and(EXPIRY.gt(now)))
                .orderBy(EXPIRY.asc(), ID.asc()).limit(20).fetch(DOCUMENT).stream().map(this::decode).toList();
    }

    private Map<String, Object> decode(String document) {
        try { return json.readValue(document); }
        catch (IOException e) { throw new IllegalStateException("Upgrade plan document unavailable", e); }
    }

    public Map<String, Object> load(String id) {
        String document = create().select(DOCUMENT).from(TABLE).where(ID.eq(id)).fetchOne(DOCUMENT);
        if (document == null) return null;
        return decode(document);
    }

    public void save(Map<String, Object> plan) {
        create().update(TABLE).set(STATE, (String) plan.get("status")).set(DOCUMENT, encode(plan))
                .where(ID.eq((String) plan.get("id"))).execute();
    }

    /** Persist intent before dispatch. A crash leaves unknown, never permission to dispatch twice. */
    public boolean claim(Map<String, Object> plan) {
        return create().update(TABLE).set(STATE, "dispatching").set(DOCUMENT, encode(plan))
                .where(ID.eq((String) plan.get("id")).and(STATE.eq("preview"))).execute() == 1;
    }

    private String encode(Map<String, Object> plan) {
        try { return json.writeValueAsString(plan); }
        catch (IOException e) { throw new IllegalStateException("Cannot persist upgrade plan", e); }
    }
}
