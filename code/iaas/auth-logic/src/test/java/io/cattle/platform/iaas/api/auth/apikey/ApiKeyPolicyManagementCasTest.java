package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.Test;

public class ApiKeyPolicyManagementCasTest {
    @Test public void historicalJsonWhitespaceIsPreservedInTheAtomicCompare() throws Exception {
        exercise(1);
    }

    @Test public void concurrentRevocationOrRevisionChangeRejectsTheWrite() throws Exception {
        ClientVisibleException failure = assertThrows(ClientVisibleException.class, () -> exercise(0));
        assertEquals(409, failure.getStatus());
        assertEquals("ApiKeyPolicyRevisionConflict", failure.getCode());
    }

    private void exercise(int changed) throws Exception {
        String original = "{  \"legacyField\" : \"retained\"  }";
        ApiKeyPolicyManagementService service = new ApiKeyPolicyManagementService();
        service.jsonMapper = new JacksonJsonMapper();
        CredentialRecord key = new CredentialRecord();
        key.setId(12L); key.setAccountId(42L); key.setState("active"); key.setKind("apiKey");
        key.setData(service.jsonMapper.readValue(original));
        Field<String> rawData = DSL.field(io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL.DATA.getQualifiedName(), String.class);
        DSLContext records = DSL.using(SQLDialect.MARIADB);
        service.setConfiguration(DSL.using(new MockConnection(context -> {
            if (context.sql().toLowerCase().startsWith("select")) {
                var result = records.newResult(rawData);
                var record = records.newRecord(rawData); record.set(rawData, original); result.add(record);
                return new MockResult[] {new MockResult(1, result)};
            }
            assertTrue(Arrays.asList(context.bindings()).contains(original));
            assertTrue(context.sql().contains("where"));
            String where = context.sql().substring(context.sql().indexOf("where"));
            for (String column : List.of("state", "removed", "kind", "account_id", "data")) assertTrue(where.contains(column));
            assertEquals("active", key.getState());
            return new MockResult[] {new MockResult(changed, null)};
        }), SQLDialect.MARIADB).configuration());
        ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
        ApiKeyPolicy closed = new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of());
        service.persist(key, codec.store(key, closed, 1), "apiKeyRestricted", Map.of());
        assertEquals("active", key.getState());
    }
}
