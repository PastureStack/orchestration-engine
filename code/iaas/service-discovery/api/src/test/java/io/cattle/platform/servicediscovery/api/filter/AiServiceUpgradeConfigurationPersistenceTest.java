package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.*;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.junit.Test;

public class AiServiceUpgradeConfigurationPersistenceTest {
    @Test public void legacyImageOnlySnapshotWithoutUuidKeepsItsExactOriginalCas() throws Exception {
        new AiServiceUpgradePersistenceTest().staleDataCannotOverwriteAConcurrentSettingsChange();
    }
    @Test public void configUpgradeCasRejectsUuidStateSettingsAndOwnerDriftAtTheCommit() throws Exception {
        for (String drift : new String[] {"uuid='other'", "state='inactive'", "data='{}'", "account_id=78", "name='changed'", "environment_id=4"}) {
            try (var c = DriverManager.getConnection("jdbc:h2:mem:config-cas;DATABASE_TO_LOWER=TRUE")) {
                c.createStatement().execute("CREATE SCHEMA cattle");
                c.createStatement().execute("CREATE TABLE cattle.service(id BIGINT, uuid VARCHAR, account_id BIGINT, environment_id BIGINT, "
                        + "name VARCHAR, state VARCHAR, removed TIMESTAMP, data CLOB, selector_container VARCHAR, selector_link VARCHAR)");
                c.createStatement().execute("INSERT INTO cattle.service VALUES(10,'exact',77,3,'web','active',NULL,'{}',NULL,NULL)");
                var p = new AiServiceUpgradePersistence(); p.json = new JacksonJsonMapper();
                p.setConfiguration(new DefaultConfiguration().set(c).set(SQLDialect.H2));
                var target = new ServiceRecord(); target.setId(10L); target.setAccountId(77L); target.setUuid("exact"); target.setData(new HashMap<>());
                var snapshot = Map.of("targetUuid", "exact", "name", "web", "stackId", 3, "data", Map.of());
                // Prove this exact snapshot can commit before changing any boundary.
                p.persist(target, snapshot);
                if (drift.startsWith("data=")) c.createStatement().execute("UPDATE cattle.service SET data='{\"fields\":{\"scale\":2}}'");
                else c.createStatement().execute("UPDATE cattle.service SET " + drift);
                var denied = assertThrows(ClientVisibleException.class, () -> p.persist(target, snapshot));
                assertEquals("UpgradePlanStale", denied.getCode());
            }
        }
    }
}
