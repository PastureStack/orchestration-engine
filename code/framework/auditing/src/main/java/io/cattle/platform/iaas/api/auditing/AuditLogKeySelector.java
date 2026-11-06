package io.cattle.platform.iaas.api.auditing;

import io.cattle.platform.api.auth.ApiResourceAccess;
import io.cattle.platform.util.type.Priority;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.AbstractSchemaPostProcessor;
import io.github.ibuildthecloud.gdapi.model.Filter;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.jooq.Configuration;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

/** An optional exact selector, ANDed with the native tenant and Key restrictions before pagination. */
public class AuditLogKeySelector extends AbstractSchemaPostProcessor implements ApiResourceAccess, Priority {
    private Configuration configuration;

    @Inject public void setConfiguration(Configuration configuration) { this.configuration = configuration; }

    private boolean supported() {
        return configuration != null && (configuration.dialect().family() == SQLDialect.MYSQL
                || configuration.dialect().family() == SQLDialect.MARIADB);
    }

    @Override public int getPriority() { return Priority.DEFAULT + 2; }

    @Override public SchemaImpl postProcess(SchemaImpl schema, SchemaFactory factory) {
        if (!"auditLog".equals(schema.getId())) return schema;
        // Each factory/role gets its own map; never mutate another cached schema.
        SchemaImpl result = new SchemaImpl(schema);
        if (supported() && readable(result)) result.getCollectionFilters().put("keyId", new Filter(List.of("eq")));
        else result.getCollectionFilters().remove("keyId");
        return result;
    }

    private static boolean readable(Schema schema) {
        var field = schema.getResourceFields() == null ? null : schema.getResourceFields().get("keyId");
        return schema.getCollectionMethods() != null && schema.getCollectionMethods().contains("GET") && field != null
                && field.isIncludeInList() && !field.isReadOnCreateOnly()
                && (field.getTransform() == null || field.getTransform().isBlank());
    }

    @Override
    public Condition constrain(ApiRequest request, SchemaFactory schemas, String type, Table<?> table) {
        if (request == null || !"GET".equals(request.getMethod()) || request.getId() != null
                || !"auditLog".equals(type) || table == null || !"audit_log".equals(table.getName())) return null;
        var conditions = request.getConditions() == null ? null : request.getConditions().get("keyId");
        if (unsupported(request.getRequestParams()) || unsupported(request.getRequestObject())) throw invalid();
        boolean supplied = supplied(request.getRequestParams()) || supplied(request.getRequestObject());
        if ((supplied || conditions != null) && !supported())
            throw new ClientVisibleException(422, "AuditKeySelectorUnsupported", "Audit Key selector is not supported", null);
        if (conditions == null) {
            if (supplied) throw invalid(); // An unadmitted selector must never silently become all history.
            return null;
        }
        if (conditions.size() != 1 || conditions.getFirst() == null
                || conditions.getFirst().getConditionType() != ConditionType.EQ
                || conditions.getFirst().getValues() == null || conditions.getFirst().getValues().size() != 1
                || !(conditions.getFirst().getValue() instanceof String value)) throw invalid();
        String key = canonical(value);
        Schema schema = schemas == null ? null : schemas.getSchema(request.getType());
        var field = schema == null || schema.getResourceFields() == null ? null : schema.getResourceFields().get("keyId");
        if (field == null || !field.isIncludeInList() || field.isReadOnCreateOnly()
                || field.getTransform() != null && !field.getTransform().isBlank())
            throw new ClientVisibleException(403, "OwnerPermissionDenied", "Audit selector is not available", null);
        Field<?> data = table.field("data");
        if (data == null) throw invalid();
        // Fixed path and expression only. BINARY keeps case and trailing spaces
        // exact. Invalid legacy JSON is a non-match, not a whole-query failure.
        Field<String> metadata = DSL.field("binary json_unquote(json_extract("
                + "case when json_valid({0}) then {0} else null end, '$.fields.keyId'))", String.class, data);
        return metadata.eq(DSL.val(key)).or(metadata.eq(DSL.val(key.substring(2))));
    }

    private static boolean supplied(Object input) {
        if (!(input instanceof Map<?, ?> values)) return false;
        return values.keySet().stream().anyMatch(key -> key instanceof String name
                && ("keyId".equals(name) || name.startsWith("keyId_")));
    }

    private static boolean unsupported(Object input) {
        return input instanceof Map<?, ?> values && values.keySet().stream()
                .anyMatch(key -> key instanceof String name && name.startsWith("keyId_"));
    }

    private static String canonical(String value) {
        if (value == null || !value.matches("1c[1-9][0-9]{0,18}")) throw invalid();
        try {
            if (Long.parseLong(value.substring(2)) <= 0) throw invalid();
            return value;
        } catch (NumberFormatException invalid) {
            throw invalid();
        }
    }

    private static ClientVisibleException invalid() {
        return new ClientVisibleException(422, "AuditKeySelectorInvalid", "Invalid audit Key selector", null);
    }
}
