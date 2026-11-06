package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.*;

import io.cattle.platform.core.model.tables.AuditLogTable;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.LinkedHashMap;
import java.util.List;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.Test;

public class AuditLogKeySelectorTest {
    private final AuditLogKeySelector selector = new AuditLogKeySelector();
    private Schema currentSchema;
    private final SchemaFactory schemas = new SchemaFactoryImpl() {
        @Override public Schema getSchema(String type) {
            return "auditLog".equals(type) ? currentSchema : null;
        }
    };

    public AuditLogKeySelectorTest() { selector.setConfiguration(DSL.using(SQLDialect.MARIADB).configuration()); }

    private ApiRequest request(String key) {
        SchemaImpl schema = new SchemaImpl(); schema.setId("auditLog");
        FieldImpl field = new FieldImpl(); field.setIncludeInList(true);
        schema.getResourceFields().put("keyId", field); currentSchema = schema;
        ApiRequest request = new ApiRequest(null, schemas); request.setType("auditLog"); request.setMethod("GET");
        request.setRequestParams(new LinkedHashMap<>()); request.setConditions(new LinkedHashMap<>());
        if (key != null) request.getConditions().put("keyId", List.of(new Condition(ConditionType.EQ, key)));
        return request;
    }

    @Test public void fixedJsonPathUsesBoundCanonicalAndLegacyValuesWithExactByteComparison() {
        for (SQLDialect dialect : List.of(SQLDialect.MARIADB, SQLDialect.MYSQL)) {
            var context = DSL.using(dialect, new Settings().withRenderSchema(false));
            var condition = selector.constrain(request("1c42"), schemas, "auditLog", AuditLogTable.AUDIT_LOG);
            var query = context.select(AuditLogTable.AUDIT_LOG.ID).from(AuditLogTable.AUDIT_LOG).where(condition);
            String sql = query.getSQL().toLowerCase(java.util.Locale.ROOT);
            assertTrue(sql.contains("binary json_unquote(json_extract(case when json_valid("));
            assertTrue(sql.contains("'$.fields.keyid'"));
            assertFalse(sql.contains("1c42")); assertFalse(sql.contains("requestobject")); assertFalse(sql.contains(" like "));
            assertEquals(List.of("1c42", "42"), query.getBindValues());
            assertEquals(2L, sql.chars().filter(c -> c == '?').count());
        }
    }

    @Test public void malformedOrUnadmittedSelectorsNeverFallBackAndOtherPathsStayUnchanged() {
        for (String key : List.of("42", "1a42", "1c0", "1c042", "1c42 ", "1c42' or 1=1", "1c9223372036854775808")) {
            var failure = assertThrows(ClientVisibleException.class,
                    () -> selector.constrain(request(key), schemas, "auditLog", AuditLogTable.AUDIT_LOG));
            assertEquals(422, failure.getStatus());
        }
        ApiRequest absent = request(null);
        assertNull(selector.constrain(absent, schemas, "auditLog", AuditLogTable.AUDIT_LOG));
        absent.getRequestParams().put("keyId", new String[] {"1c42"});
        assertThrows(ClientVisibleException.class, () -> selector.constrain(absent, schemas, "auditLog", AuditLogTable.AUDIT_LOG));
        ApiRequest unsupported = request("1c42"); unsupported.getRequestParams().put("keyId_ne", new String[] {"1c43"});
        assertThrows(ClientVisibleException.class, () -> selector.constrain(unsupported, schemas, "auditLog", AuditLogTable.AUDIT_LOG));
        ApiRequest many = request("1c42"); many.getConditions().put("keyId", List.of(new Condition(ConditionType.EQ, List.of("1c42", "1c43"))));
        assertThrows(ClientVisibleException.class, () -> selector.constrain(many, schemas, "auditLog", AuditLogTable.AUDIT_LOG));
        ApiRequest hidden = request("1c42"); currentSchema = new SchemaImpl();
        assertEquals(403, assertThrows(ClientVisibleException.class,
                () -> selector.constrain(hidden, schemas, "auditLog", AuditLogTable.AUDIT_LOG)).getStatus());
        ApiRequest other = request("malformed"); other.setType("service");
        assertNull(selector.constrain(other, schemas, "service", AuditLogTable.AUDIT_LOG));
        other = request("malformed"); other.setMethod("PUT");
        assertNull(selector.constrain(other, schemas, "auditLog", AuditLogTable.AUDIT_LOG));
        other = request("malformed"); other.setId("42");
        assertNull(selector.constrain(other, schemas, "auditLog", AuditLogTable.AUDIT_LOG));
    }


}
