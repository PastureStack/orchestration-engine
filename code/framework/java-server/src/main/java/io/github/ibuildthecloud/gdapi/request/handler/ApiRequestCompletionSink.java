package io.github.ibuildthecloud.gdapi.request.handler;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

/** Called once after the response status is final, while ApiContext is still available. */
public interface ApiRequestCompletionSink {
    void complete(ApiRequest request, Throwable failure);
}
