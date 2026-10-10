package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractResponseGenerator;
import java.util.Map;
import java.util.Set;
import jakarta.inject.Inject;

/** Validate policy input before generic schema sanitization silently drops fields. */
public class ApiKeyPolicyInputHandler extends AbstractResponseGenerator {
    @Inject ApiKeyPolicyManagementService policies;

    @Override
    protected void generate(ApiRequest request) {
        if (request.getAction() != null || request.getSchemaFactory() == null) return;
        Schema schema = request.getSchemaFactory().getSchema(request.getType());
        if (schema == null) return; // Leave missing schemas and method authorization to the existing validator.
        Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        if ("POST".equals(request.getMethod()) && "apiKeyPolicyPreview".equals(schema.getId())) {
            policies.validatePreviewInput(input);
        } else if (input.containsKey(ApiKeyPolicyCodec.POLICY)
                && Set.of(CredentialConstants.TYPE, CredentialConstants.KIND_API_KEY,
                        CredentialConstants.KIND_API_KEY_RESTRICTED).contains(schema.getId())) {
            if ("PUT".equals(request.getMethod())) policies.validateInput(input, false);
            else if ("POST".equals(request.getMethod())) policies.validateInput(input, true);
        }
        // Legacy/full clients without policy input keep the existing schema semantics.
        // Do not retain raw bodies or credential secrets in request attributes or audit logs.
    }
}
