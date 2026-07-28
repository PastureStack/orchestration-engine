package io.cattle.platform.iaas.api.auth.identity;

final class PreparedProviderSwitch {

    private final String code;
    private final long expiresAt;

    PreparedProviderSwitch(String code, long expiresAt) {
        this.code = code;
        this.expiresAt = expiresAt;
    }

    String getCode() {
        return code;
    }

    long getExpiresAt() {
        return expiresAt;
    }
}
