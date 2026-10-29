package io.cattle.platform.servicediscovery.api.filter;

import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import org.jooq.Field;
import org.jooq.impl.DSL;

/** CAS at the existing upgrade-validation commit, so preview cannot overwrite newer settings. */
@Named
public class AiServiceUpgradePersistence extends AbstractJooqDao {
    @Inject JsonMapper json;
    public void persist(Service service, Map<?, ?> snapshot) {
        Field<String> raw = DSL.field(SERVICE.DATA.getQualifiedName(), String.class);
        String original = create().select(raw).from(SERVICE).where(SERVICE.ID.eq(service.getId())).fetchOne(raw);
        try {
            Object data = original == null ? null : json.readValue(original);
            if (!Objects.equals(snapshot.get("data"), data)) throw stale();
        } catch (IOException invalid) { throw stale(); }
        Long stackId = snapshot.get("stackId") == null ? null : ((Number) snapshot.get("stackId")).longValue();
        int changed = create().update(SERVICE).set(SERVICE.DATA, service.getData())
                .where(SERVICE.ID.eq(service.getId()).and(SERVICE.ACCOUNT_ID.eq(service.getAccountId()))
                        .and(SERVICE.STATE.eq("active")).and(SERVICE.REMOVED.isNull())
                        .and(SERVICE.NAME.eq((String) snapshot.get("name")))
                        .and(snapshot.get("selectorContainer") == null ? SERVICE.SELECTOR_CONTAINER.isNull()
                                : SERVICE.SELECTOR_CONTAINER.eq((String) snapshot.get("selectorContainer")))
                        .and(snapshot.get("selectorLink") == null ? SERVICE.SELECTOR_LINK.isNull()
                                : SERVICE.SELECTOR_LINK.eq((String) snapshot.get("selectorLink")))
                        .and(stackId == null ? SERVICE.STACK_ID.isNull() : SERVICE.STACK_ID.eq(stackId))
                        .and(original == null ? raw.isNull() : raw.eq(original))).execute();
        if (changed != 1) throw stale();
    }
    private ClientVisibleException stale() { return new ClientVisibleException(409, "UpgradePlanStale", "UpgradePlanStale", null); }
}
