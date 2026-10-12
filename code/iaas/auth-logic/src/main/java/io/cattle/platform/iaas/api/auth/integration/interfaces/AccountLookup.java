package io.cattle.platform.iaas.api.auth.integration.interfaces;

import io.cattle.platform.core.model.Account;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;

public interface AccountLookup extends Configurable{

    /**
     * Claim a reserved Basic username for a dedicated credential protocol.
     * Unclaimed Basic credentials continue through BasicAuth, never a cookie.
     */
    default boolean acceptsBasicUsername(String username) {
        return false;
    }

    Account getAccount(ApiRequest request);

    boolean challenge(ApiRequest request);

}
