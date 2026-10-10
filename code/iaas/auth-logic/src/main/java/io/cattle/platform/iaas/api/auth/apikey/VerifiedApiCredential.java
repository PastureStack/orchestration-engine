package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.core.model.Account;

/** DAO result contains no public or secret credential values. */
public record VerifiedApiCredential(Account account, ApiKeyCredentialContext context, String denialCode) {
    public VerifiedApiCredential(Account account, ApiKeyCredentialContext context) {
        this(account, context, null);
    }
}
