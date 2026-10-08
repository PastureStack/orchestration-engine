package io.cattle.platform.api.auth;

import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import org.jooq.Condition;
import org.jooq.Table;
import java.util.List;

/** Optional narrowing of existing RBAC, applied before SQL pagination. */
public interface ApiResourceAccess {
    Condition constrain(ApiRequest request, SchemaFactory schemaFactory, String type, Table<?> table);
    default List<?> filterCollection(ApiRequest request, List<?> values) { return values; }
}
