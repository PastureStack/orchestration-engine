package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.*;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultConfiguration;
import org.junit.Test;

public class AiServiceUpgradePersistenceTest {
    @Test public void staleDataCannotOverwriteAConcurrentSettingsChange() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:ai-upgrade-cas;DATABASE_TO_LOWER=TRUE")) {
            connection.createStatement().execute("CREATE SCHEMA cattle");
            connection.createStatement().execute("CREATE TABLE cattle.service(id BIGINT, account_id BIGINT, environment_id BIGINT, name VARCHAR, state VARCHAR, removed TIMESTAMP, data CLOB, selector_container VARCHAR, selector_link VARCHAR)");
            connection.createStatement().execute("INSERT INTO cattle.service VALUES(10,77,3,'web','active',NULL,'{\"fields\":{\"scale\":1}}',NULL,NULL)");
            AiServiceUpgradePersistence persistence = new AiServiceUpgradePersistence();
            persistence.json = new JacksonJsonMapper();
            persistence.setConfiguration(new DefaultConfiguration().set(connection).set(SQLDialect.H2));
            ServiceRecord target = new ServiceRecord(); target.setId(10L); target.setAccountId(77L);
            target.setData(new HashMap<>()); DataAccessor.setField(target, "scale", 1); DataAccessor.setField(target, "launchConfig", Map.of("imageUuid", "docker:new"));
            Map<String, Object> snapshot = Map.of("name", "web", "stackId", 3, "data", Map.of("fields", Map.of("scale", 1)));
            persistence.persist(target, snapshot);
            try { persistence.persist(target, snapshot); fail("Old snapshot must be rejected"); }
            catch (ClientVisibleException expected) { assertEquals("UpgradePlanStale", expected.getCode()); }
            try (var result = connection.createStatement().executeQuery("SELECT data FROM cattle.service")) {
                assertTrue(result.next());
                Map<String, Object> data = persistence.json.readValue(result.getString(1));
                assertEquals(Map.of("fields", Map.of("scale", 1, "launchConfig", Map.of("imageUuid", "docker:new"))), data);
            }
        }
    }
}
