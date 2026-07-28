package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.model.Account;

public final class ProviderSwitchTicket {

    private final Account account;
    private final String provider;
    private final Identity identity;

    ProviderSwitchTicket(Account account, String provider, Identity identity) {
        this.account = account;
        this.provider = provider;
        this.identity = identity;
    }

    public Account getAccount() {
        return account;
    }

    public String getProvider() {
        return provider;
    }

    public Identity getIdentity() {
        return identity;
    }
}
