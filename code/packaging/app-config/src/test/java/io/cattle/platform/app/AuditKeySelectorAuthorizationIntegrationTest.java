package io.cattle.platform.app;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.core.model.tables.AuditLogTable;
import io.cattle.platform.iaas.api.auditing.AuditLogKeySelector;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.Test;

public class AuditKeySelectorAuthorizationIntegrationTest {
    private final AuditLogKeySelector selector = new AuditLogKeySelector();
    private final SchemaFactory schemas = mock(SchemaFactory.class);

    public AuditKeySelectorAuthorizationIntegrationTest() { selector.setConfiguration(DSL.using(SQLDialect.MARIADB).configuration()); }

    private ApiRequest request(String key) {
        SchemaImpl schema = new SchemaImpl(); schema.setId("auditLog");
        FieldImpl field = new FieldImpl(); field.setIncludeInList(true);
        schema.getResourceFields().put("keyId", field); when(schemas.getSchema("auditLog")).thenReturn(schema);
        ApiRequest request = new ApiRequest(null, schemas); request.setType("auditLog"); request.setMethod("GET");
        request.setRequestParams(new LinkedHashMap<>()); request.setConditions(new LinkedHashMap<>());
        if (key != null) request.getConditions().put("keyId", List.of(new Condition(ConditionType.EQ, key)));
        return request;
    }

    @Test public void nativeKeyScopeAndTenantBoundsRemainAndedBeforePagination() throws Exception {
        var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
        when(schemas.getBaseType(anyString())).thenAnswer(call -> call.getArgument(0));
        formatter.setTypeMappings(Map.of("credential", "c", "apiKey", "c", "auditLog", "al"));
        ApiContext.newContext().setIdFormatter(formatter);
        try {
        ApiRequest request = request("1c42");
        var target = mock(ApiKeyTargetResolver.class); when(target.parseScopeId("auditLog", "1al5")).thenReturn(5L);
        var access = new ApiKeyAuthorizationService();
        var targets = ApiKeyAuthorizationService.class.getDeclaredField("targets"); targets.setAccessible(true); targets.set(access, target);
        var allow = new ApiKeyPolicy.Rule("audit-row", ApiKeyPolicy.Effect.ALLOW,
                ApiKeyPolicy.Scope.resource("auditLog", "1al5"), Set.of("read"));
        var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(allow));
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(8, 1, "apiKey", 3, policy));
        var context = DSL.using(SQLDialect.MARIADB, new Settings().withRenderSchema(false));
        var key = access.constrain(request, schemas, "auditLog", AuditLogTable.AUDIT_LOG);
        var metadata = selector.constrain(request, schemas, "auditLog", AuditLogTable.AUDIT_LOG);
        var query = context.select(AuditLogTable.AUDIT_LOG.ID).from(AuditLogTable.AUDIT_LOG)
                .where(AuditLogTable.AUDIT_LOG.ACCOUNT_ID.eq(1L))
                .and(AuditLogTable.AUDIT_LOG.CREATED.ge(new java.sql.Timestamp(0)))
                .and(key).and(metadata).orderBy(AuditLogTable.AUDIT_LOG.ID.desc()).limit(25);
        String sql = query.getSQL().toLowerCase(java.util.Locale.ROOT);
        assertTrue(sql.indexOf("account_id") < sql.indexOf("json_extract"));
        String pagination = sql.contains(" limit ") ? " limit " : " fetch next ";
        assertTrue(sql.contains(pagination) && sql.indexOf("json_extract") < sql.indexOf(pagination));
        var bindings = query.getBindValues();
        assertEquals(6, bindings.size());
        assertEquals(1L, ((Number) bindings.get(0)).longValue());
        assertEquals(0L, ((java.util.Date) bindings.get(1)).getTime());
        assertEquals(5L, ((Number) bindings.get(2)).longValue());
        assertEquals("1c42", bindings.get(3)); assertEquals("42", bindings.get(4));
        assertEquals(25, ((Number) bindings.get(5)).intValue());
        var deny = new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.global(), Set.of("read"));
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(8, 1, "apiKey", 3,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(allow, deny))));
        String denied = context.renderInlined(access.constrain(request, schemas, "auditLog", AuditLogTable.AUDIT_LOG));
        assertTrue(denied.contains("not") && denied.replace(" ", "").contains("coalesce(true,false)"));
        } finally {
            ApiContext.remove();
        }
    }
    @Test public void unsupportedDialectsPreserveOldBoundedReadsAndRejectExplicitSelector() throws Exception {
        for (SQLDialect dialect : List.of(SQLDialect.H2, SQLDialect.HSQLDB)) {
            var unsupported = new AuditLogKeySelector(); unsupported.setConfiguration(DSL.using(dialect).configuration());
            var schema = new SchemaImpl(); schema.setId("auditLog"); schema.setCollectionMethods(List.of("GET"));
            var field = new FieldImpl(); field.setIncludeInList(true); schema.getResourceFields().put("keyId", field);
            schema.getCollectionFilters().put("keyId", new io.github.ibuildthecloud.gdapi.model.Filter(List.of("eq")));
            schema.getCollectionFilters().put("id", new io.github.ibuildthecloud.gdapi.model.Filter(List.of("eq")));
            var projected = unsupported.postProcess(schema, schemas);
            assertNotSame(schema, projected); assertFalse(projected.getCollectionFilters().containsKey("keyId"));
            assertTrue(schema.getCollectionFilters().containsKey("keyId")); assertTrue(projected.getCollectionFilters().containsKey("id"));
            when(schemas.getSchema("auditLog")).thenReturn(projected);
            assertNull(unsupported.constrain(request(null), schemas, "auditLog", AuditLogTable.AUDIT_LOG));
            assertEquals("AuditKeySelectorUnsupported", assertThrows(ClientVisibleException.class,
                    () -> unsupported.constrain(request("1c42"), schemas, "auditLog", AuditLogTable.AUDIT_LOG)).getCode());
            String url = dialect == SQLDialect.H2 ? "jdbc:h2:mem:auditSelectorCompat" : "jdbc:hsqldb:mem:auditSelectorCompat";
            try (var connection = java.sql.DriverManager.getConnection(url, "sa", "")) {
                var context = DSL.using(connection, dialect, new Settings().withRenderSchema(false));
                context.execute("create table \"audit_log\" (\"id\" bigint primary key, \"account_id\" bigint, \"data\" varchar(500))");
                context.execute("insert into \"audit_log\" values (1, 1, ?), (2, 2, ?)",
                        "{\"fields\":{\"keyId\":\"1c42\"}}", "{\"fields\":{\"keyId\":\"1c43\"}}");
                var condition = unsupported.constrain(request(null), schemas, "auditLog", AuditLogTable.AUDIT_LOG);
                var query = context.select(AuditLogTable.AUDIT_LOG.ID).from(AuditLogTable.AUDIT_LOG)
                        .where(AuditLogTable.AUDIT_LOG.ACCOUNT_ID.eq(1L)).and(condition)
                        .orderBy(AuditLogTable.AUDIT_LOG.ID.desc()).limit(1);
                assertFalse(query.getSQL().toLowerCase(java.util.Locale.ROOT).contains("json_"));
                assertEquals(1, query.fetch().size());
                if (dialect == SQLDialect.HSQLDB) context.execute("shutdown");
            }
        }
        var schema = new SchemaImpl(); schema.setId("auditLog"); schema.setCollectionMethods(List.of("GET"));
        var field = new FieldImpl(); field.setIncludeInList(true); schema.getResourceFields().put("keyId", field);
        assertTrue(selector.postProcess(schema, schemas).getCollectionFilters().containsKey("keyId"));
        assertFalse(schema.getCollectionFilters().containsKey("keyId"));
        field.setIncludeInList(false);
        assertFalse(selector.postProcess(schema, schemas).getCollectionFilters().containsKey("keyId"));
    }

}
