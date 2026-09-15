package io.cattle.platform.iaas.api.auth.dao;

import io.cattle.platform.core.model.AuthToken;

public interface AuthTokenDao {

    AuthToken getTokenByKey(String key);

    AuthToken createToken(String jwt, String provider, long accountId, long authenticatedAsAccountId);

    AuthToken createToken(String jwt, String provider, long accountId, long authenticatedAsAccountId,
            String clientSessionId);

    AuthToken getTokenByAccountId(long accountId);

    String getNewestClientSessionId(long authenticatedAsAccountId, long tokenAccountId);

    void deletePreviousTokens(long authenticatedAsAccountId, long tokenAccountId, String keepKey);

    int deleteTokensForAccount(long authenticatedAsAccountId);

    boolean deleteToken(String key);
}
