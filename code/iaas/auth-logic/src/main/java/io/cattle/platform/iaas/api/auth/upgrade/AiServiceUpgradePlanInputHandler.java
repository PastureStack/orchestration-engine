package io.cattle.platform.iaas.api.auth.upgrade;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractResponseGenerator;

/** Preserve only invalid typed preview input presence before native coercion. */
public class AiServiceUpgradePlanInputHandler extends AbstractResponseGenerator {
    @Override
    protected void generate(ApiRequest request) {
        if (!"POST".equals(request.getMethod()) || request.getId() != null || request.getAction() != null
                || request.getSchemaFactory() == null) return;
        var schema = request.getSchemaFactory().getSchema(request.getType());
        if (schema == null || !"aiServiceUpgradePlan".equals(schema.getId())) return;
        AiServiceUpgradePlanService.captureOriginalPreview(request);
    }
}
