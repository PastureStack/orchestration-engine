package io.cattle.platform.iaas.api.auth.ai.audit;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractResponseGenerator;

/** Capture only the typed owner's closed-field violation before ordinary schema sanitization. */
public class AiAuditQueryInputHandler extends AbstractResponseGenerator {
    @Override
    protected void generate(ApiRequest request) {
        if (!"POST".equals(request.getMethod()) || request.getId() != null || request.getAction() != null
                || request.getSchemaFactory() == null) return;
        var schema = request.getSchemaFactory().getSchema(request.getType());
        if (schema == null || !"aiAuditQuery".equals(schema.getId())) return;
        AiAuditQueryService.captureOriginalFields(request);
    }
}
