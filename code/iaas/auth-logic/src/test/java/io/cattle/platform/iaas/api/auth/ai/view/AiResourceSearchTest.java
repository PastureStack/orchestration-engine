package io.cattle.platform.iaas.api.auth.ai.view;

import static io.cattle.platform.core.model.tables.VolumeTable.VOLUME;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.object.jooq.utils.JooqUtils;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.After;
import org.junit.Test;

public class AiResourceSearchTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void nativeNameFilterFindsLateRowsInOneBoundedAuthorizedRead() throws Exception {
        assertNativeNameSearch("late-繁體-volume", "late-繁體");
    }

    @Test public void nativePatternCharactersStayLiteralAndDoNotHideLateMatches() throws Exception {
        assertNativeNameSearch("late-%_\\'漢字-volume", "%_\\'漢字");
    }

    private void assertNativeNameSearch(String name, String query) throws Exception {
        var f = new AiResourceViewServiceTest.Fixture();
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:ai-search-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;IGNORECASE=TRUE")) {
            DSLContext db = DSL.using(connection, SQLDialect.H2);
            db.createSchemaIfNotExists(VOLUME.getSchema()).execute();
            db.createTable(VOLUME).column("id", SQLDataType.BIGINT).column("name", SQLDataType.VARCHAR)
                    .column("account_id", SQLDataType.BIGINT).execute();
            for (long id = 1; id <= 120; id++)
                db.insertInto(VOLUME, VOLUME.ID, VOLUME.NAME, VOLUME.ACCOUNT_ID)
                        .values(id, "unrelated-" + id, 77L).execute();
            db.insertInto(VOLUME, VOLUME.ID, VOLUME.NAME, VOLUME.ACCOUNT_ID).values(999L, name, 77L).execute();
            db.insertInto(VOLUME, VOLUME.ID, VOLUME.NAME, VOLUME.ACCOUNT_ID).values(1000L, name, 88L).execute();
            AtomicInteger reads = new AtomicInteger();
            when(f.manager.list(eq("volume"), any(ApiRequest.class))).thenAnswer(call -> {
                reads.incrementAndGet(); ApiRequest request = call.getArgument(1);
                var where = VOLUME.ACCOUNT_ID.eq(77L); // The ordinary owner's existing tenant restriction.
                for (Condition condition : request.getConditions().getOrDefault("name", List.of())) {
                    assertEquals(ConditionType.LIKE, condition.getConditionType());
                    where = where.and(NativeConditions.nativeCondition(VOLUME.NAME, condition));
                }
                List<Resource> rows = new ArrayList<>();
                for (var record : db.select(VOLUME.ID, VOLUME.NAME).from(VOLUME).where(where).orderBy(VOLUME.ID)
                        .limit(request.getPagination().getLimit().intValue()).fetch())
                    rows.add(new ResourceImpl(record.get(VOLUME.ID).toString(), "volume",
                            new java.util.LinkedHashMap<>(Map.of("name", record.get(VOLUME.NAME), "state", "inactive"))));
                return rows;
            });
            Map<String, Object> result = f.service.read(f.request(Map.of("resourceType", "volume", "query", query)));
            List<?> rows = (List<?>) result.get("data");
            assertEquals(1, reads.get()); assertEquals(1, rows.size());
            assertEquals("999", ((Map<?, ?>) rows.getFirst()).get("id"));
            assertEquals(name, ((Map<?, ?>) rows.getFirst()).get("name"));
            assertNull(result.get("nextMarker"));
            verify(f.keyAccess).authorize(any(), any());
        }
    }

    @Test public void memoryOwnerFiltersBeforeItsFiftyRowPage() {
        var f = new AiResourceViewServiceTest.Fixture();
        for (int id = 1; id <= 70; id++) f.rows.add(new ResourceImpl(Integer.toString(id), "project",
                new java.util.LinkedHashMap<>(Map.of("name", "unrelated-" + id))));
        f.rows.add(new ResourceImpl("999", "project", new java.util.LinkedHashMap<>(Map.of("name", "wanted-繁體"))));
        Map<String, Object> result = f.service.read(f.request(Map.of("resourceType", "project", "query", "wanted")));
        assertEquals(1, ((List<?>) result.get("data")).size());
        assertEquals("999", ((Map<?, ?>) ((List<?>) result.get("data")).getFirst()).get("id"));
        assertNull(result.get("nextMarker"));
        verify(f.manager).list(eq("project"), any(ApiRequest.class));
    }

    private static class NativeConditions extends JooqUtils {
        static org.jooq.Condition nativeCondition(TableField<?, ?> field, Condition condition) {
            return toCondition(field, condition);
        }
    }
}
