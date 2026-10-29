package io.cattle.platform.docker.api.transform;

import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.docker.transform.DockerTransformer;
import io.cattle.platform.iaas.api.request.handler.ScriptsHandler;
import io.cattle.platform.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.RequestUtils;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.io.IOUtils;

public class TransformInspect implements ScriptsHandler {

    @Inject
    DockerTransformer transformer;

    @Inject
    JsonMapper jsonMapper;
    @Inject java.util.List<io.cattle.platform.api.auth.CatalogLaunchTransformAuthorization> catalogAuthorizers = java.util.Collections.emptyList();

    @Override
    public boolean handle(ApiRequest request) throws IOException {
        if (!"transform".equals(request.getId())) {
            return false;
        }

        request.setResponseContentType("application/json");

        Map<String, Object> reqObj = getObject(request);
        if (reqObj == null) {
            return true;
        }
        if (catalogAuthorizers.isEmpty() && delegated(request)) throw new ClientVisibleException(503, "CatalogLaunchUnavailable");
        for (var authorizer : catalogAuthorizers) authorizer.authorize(request, reqObj);

        Instance instance = new InstanceRecord();
        transformer.transform(reqObj, instance);
        request.setResponseObject(instance);
        return true;
    }

    protected Map<String, Object> getObject(ApiRequest request) throws IOException {
        if (!RequestUtils.mayHaveBody(request.getMethod())) {
            return null;
        }

        InputStream is = request.getInputStream();
        if (is == null) {
            return null;
        }

        byte[] content = delegated(request) ? is.readNBytes(1048577) : IOUtils.toByteArray(is);
        if (delegated(request) && content.length > 1048576) throw new ClientVisibleException(422, "CatalogLaunchInputInvalid");
        if (content.length == 0) {
            return null;
        }

        try {
            return jsonMapper.readValue(content);
        } catch (IOException e) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, ValidationErrorCodes.INVALID_BODY_CONTENT);
        }
    }

    private boolean delegated(ApiRequest request) {
        return request.getServletContext() != null && request.getServletContext().getRequest()
                .getHeader(io.cattle.platform.api.auth.CatalogLaunchTransformAuthorization.HEADER) != null;
    }
}
