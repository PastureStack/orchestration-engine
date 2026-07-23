package io.cattle.platform.iaas.api.request.handler;

import java.util.List;

interface ProxySettings {

    boolean allowProxy();

    List<String> whitelist();

    long connectTimeoutMillis();

    long requestTimeoutMillis();
}
