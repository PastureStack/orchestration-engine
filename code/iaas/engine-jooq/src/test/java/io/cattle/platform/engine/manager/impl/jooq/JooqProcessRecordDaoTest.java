package io.cattle.platform.engine.manager.impl.jooq;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.log.ProcessLog;
import io.cattle.platform.json.JacksonJsonMapper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class JooqProcessRecordDaoTest {

    @Test
    public void replayBatchSizeReadsDynamicConfigThroughWrapper() throws Exception {
        final String key = "process.replay.batch.size";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "37");

            assertEquals(Integer.valueOf(37), batch().get());
        } finally {
            clearProperty(key);
        }
    }

    @Test
    public void convertToMapUsesStringObjectJsonMap() {
        TestJooqProcessRecordDao dao = new TestJooqProcessRecordDao();
        ProcessLog log = new ProcessLog();
        log.setUuid("process-log-id");

        Map<String, Object> result = dao.convertToMap(new ProcessRecord(), log);

        assertEquals("process-log-id", result.get("uuid"));
        assertTrue(result.get("executions") instanceof List);
    }

    private ConfigProperty<?> batch() throws Exception {
        Field field = JooqProcessRecordDao.class.getDeclaredField("BATCH");
        field.setAccessible(true);
        return ConfigProperty.class.cast(field.get(null));
    }

    private void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static class TestJooqProcessRecordDao extends JooqProcessRecordDao {
        TestJooqProcessRecordDao() {
            jsonMapper = new JacksonJsonMapper();
        }
    }
}
