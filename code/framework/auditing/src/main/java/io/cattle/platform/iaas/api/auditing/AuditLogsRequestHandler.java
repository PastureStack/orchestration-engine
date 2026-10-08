package io.cattle.platform.iaas.api.auditing;

import io.cattle.platform.api.auth.Policy;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractApiRequestHandler;

import java.io.IOException;

import jakarta.inject.Inject;

public class AuditLogsRequestHandler extends AbstractApiRequestHandler {

    @Inject
    AuditService auditService;

    @Override
    public void handle(ApiRequest request) throws IOException {
        // Key requests are recorded by the response-finally sink, including EOF.
        if (!AuditServiceImpl.isApiKeyRequest(request) && !AuditServiceImpl.isApiKeyGovernanceRequest(request)
                && !Schema.Method.GET.isMethod(request.getMethod())){
            request.setAttribute("requestEndTime", System.currentTimeMillis());
            Policy policy = (Policy) ApiContext.getContext().getPolicy();
            auditService.logRequest(request, policy);
        }
    }

    @Override
    public boolean handleException(ApiRequest request, Throwable error) throws IOException {
        // Completion is deferred until the actual servlet response status is known.
        return false;
    }
}
