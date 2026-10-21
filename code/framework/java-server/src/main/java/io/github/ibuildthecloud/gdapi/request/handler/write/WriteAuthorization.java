package io.github.ibuildthecloud.gdapi.request.handler.write;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

/** Authorization barriers inside the write delegate's existing transaction. */
public interface WriteAuthorization {
    void beforeWrite(ApiRequest request);

    /** A denial here must roll back the manager's writes before the response is sent. */
    void beforeCommit(ApiRequest request);
}
