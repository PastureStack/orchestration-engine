package io.github.ibuildthecloud.gdapi.request.handler.write;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.ApiRequestHandler;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import jakarta.servlet.ServletException;

public class DefaultReadWriteApiDelegate implements ReadWriteApiDelegate {

    List<ApiRequestHandler> handlers;
    List<WriteAuthorization> writeAuthorizations = Collections.emptyList();

    @Override
    public void read(ApiRequest request) throws IOException {
        handle(request);
    }

    @Override
    public void write(ApiRequest request) throws IOException {
        for (WriteAuthorization authorization : writeAuthorizations) {
            authorization.beforeWrite(request);
        }
        handle(request);
        for (WriteAuthorization authorization : writeAuthorizations) {
            authorization.beforeCommit(request);
        }
    }

    protected void handle(ApiRequest request) throws IOException {
        for (ApiRequestHandler handler : handlers) {
            handler.handle(request);
        }
    }

    public List<ApiRequestHandler> getHandlers() {
        return handlers;
    }

    @Override
    public void setHandlers(List<ApiRequestHandler> handlers) {
        this.handlers = handlers;
    }

    public void setWriteAuthorizations(List<WriteAuthorization> writeAuthorizations) {
        this.writeAuthorizations = List.copyOf(writeAuthorizations);
    }

    @Override
    public boolean handleException(ApiRequest request, Throwable e) throws IOException, ServletException {
        for (ApiRequestHandler handler : handlers) {
            if (handler.handleException(request, e)) {
                return true;
            }
        }
        return false;
    }

}
