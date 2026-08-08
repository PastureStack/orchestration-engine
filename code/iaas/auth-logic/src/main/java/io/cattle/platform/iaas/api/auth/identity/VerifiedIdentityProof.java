package io.cattle.platform.iaas.api.auth.identity;

final class VerifiedIdentityProof {

    private final String provider;
    private final String externalIdType;
    private final String externalId;
    private final String name;
    private final String login;
    private final String replayKey;

    VerifiedIdentityProof(String provider, String externalIdType, String externalId, String name,
                          String login, String replayKey) {
        this.provider = provider;
        this.externalIdType = externalIdType;
        this.externalId = externalId;
        this.name = name;
        this.login = login;
        this.replayKey = replayKey;
    }

    String getProvider() {
        return provider;
    }

    String getExternalIdType() {
        return externalIdType;
    }

    String getExternalId() {
        return externalId;
    }

    String getName() {
        return name;
    }

    String getLogin() {
        return login;
    }

    String getReplayKey() {
        return replayKey;
    }
}
