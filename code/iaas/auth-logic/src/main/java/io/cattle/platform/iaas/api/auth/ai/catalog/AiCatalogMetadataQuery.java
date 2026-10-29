package io.cattle.platform.iaas.api.auth.ai.catalog;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Closed read request. Neither a transport nor a cache database ID is input. */
public record AiCatalogMetadataQuery(String query, String marker, String templateId, boolean versions, String versionId) {
    static final String TYPE = "aiCatalogMetadataView";
    static final String LOGICAL_ID = "ctm-[0-9a-f]{64}";
    static final String CURSOR = "cmc-[A-Za-z0-9_-]{76}";
    static final String VERSION_ID = "cvm-[0-9a-f]{64}";
    public AiCatalogMetadataQuery(String query, String marker, String templateId) { this(query, marker, templateId, false, null); }
    public AiCatalogMetadataQuery {
        if (query != null && (query.length() > 256 || query.indexOf('\0') >= 0)
                || marker != null && !marker.matches(CURSOR)
                || templateId != null && (!templateId.matches(LOGICAL_ID) || query != null || marker != null && !versions)
                || versions && templateId == null
                || versionId != null && (!versions || !versionId.matches(VERSION_ID) || marker != null))
            throw error(422, "CatalogMetadataInputInvalid");
    }
    static AiCatalogMetadataQuery parse(ApiRequest request) {
        if (!TYPE.equals(request.getType()) || !"POST".equals(request.getMethod())
                || request.getId() != null || request.getAction() != null || request.getLink() != null)
            throw error(422, "CatalogMetadataInputInvalid");
        Map<String, String> values = new LinkedHashMap<>();
        boolean versions = false;
        Object body = request.getRequestObject();
        if (body != null && !(body instanceof Map<?, ?>)) throw error(422, "CatalogMetadataInputInvalid");
        if (body instanceof Map<?, ?> input) for (var entry : input.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !Set.of("type", "query", "marker", "templateId", "versions", "versionId").contains(name))
                throw error(422, "CatalogMetadataInputInvalid");
            if ("versions".equals(name)) {
                if (!Boolean.TRUE.equals(entry.getValue())) throw error(422, "CatalogMetadataInputInvalid");
                versions = true; continue;
            }
            if (!(entry.getValue() instanceof String value)) throw error(422, "CatalogMetadataInputInvalid");
            if ("type".equals(name)) { if (!TYPE.equals(value)) throw error(422, "CatalogMetadataInputInvalid"); }
            else values.put(name, value);
        }
        return new AiCatalogMetadataQuery(values.get("query"), values.get("marker"), values.get("templateId"), versions, values.get("versionId"));
    }
    static ClientVisibleException error(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
