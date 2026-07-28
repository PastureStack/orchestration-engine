package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;

public class ProviderSwitchTicketService {

    private static final int SECRET_BYTES = 32;
    private static final long MIN_TTL_SECONDS = 60L;
    private static final long MAX_TTL_SECONDS = 300L;
    private static final ConfigProperty<Long> TTL_SECONDS =
            ArchaiusUtil.getLongProperty("api.auth.provider.switch.ticket.seconds");

    private final SecureRandom secureRandom = new SecureRandom();

    @Inject
    AuthDao authDao;
    @Inject
    AccountDao accountDao;

    PreparedProviderSwitch prepare(Account account, VerifiedIdentityProof proof) {
        if (proof == null) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidProviderSwitchTarget",
                    "A verified identity is required.", null);
        }
        Identity identity = new Identity(proof.getExternalIdType(), proof.getExternalId(), proof.getName(),
                null, null, proof.getLogin(), true);
        return prepare(account, proof.getProvider(), identity);
    }

    PreparedProviderSwitch prepare(Account account, String provider, Identity identity) {
        if (account == null || identity == null || StringUtils.isBlank(provider)
                || StringUtils.isBlank(identity.getExternalIdType())
                || StringUtils.isBlank(identity.getExternalId())
                || !accountDao.isActiveAccount(account)
                || !AccountConstants.ADMIN_KIND.equalsIgnoreCase(account.getKind())) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, "InvalidProviderSwitchTarget",
                    "An active system-administrator target and verified identity are required.", null);
        }

        byte[] secret = new byte[SECRET_BYTES];
        secureRandom.nextBytes(secret);
        String code = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String ticketKey = ticketKey(code);
        long expiresAt = System.currentTimeMillis() + ttlSeconds() * 1000L;

        Map<String, Object> data = new HashMap<>();
        data.put("provider", provider);
        data.put("externalIdType", identity.getExternalIdType());
        data.put("externalId", identity.getExternalId());
        data.put("name", identity.getName());
        data.put("login", identity.getLogin());
        data.put("expiresAt", expiresAt);
        authDao.createProviderSwitchTicket(account, ticketKey, data);
        return new PreparedProviderSwitch(code, expiresAt);
    }

    ProviderSwitchTicket consume(String code) {
        String activeProvider = SecurityConstants.AUTH_PROVIDER.get();
        if (StringUtils.isBlank(code) || StringUtils.isBlank(activeProvider)) {
            return null;
        }
        Credential credential = authDao.consumeProviderSwitchTicket(ticketKey(code), activeProvider);
        if (credential == null || credential.getData() == null) {
            return null;
        }
        Account account = authDao.getAccountById(credential.getAccountId());
        if (account == null || !accountDao.isActiveAccount(account)) {
            return null;
        }
        Map<String, Object> data = credential.getData();
        String provider = string(data.get("provider"));
        String externalIdType = string(data.get("externalIdType"));
        String externalId = string(data.get("externalId"));
        if (StringUtils.isAnyBlank(provider, externalIdType, externalId)) {
            return null;
        }
        Identity identity = new Identity(externalIdType, externalId, string(data.get("name")),
                null, null, string(data.get("login")), true);
        return new ProviderSwitchTicket(account, provider, identity);
    }

    boolean consumeForActivation(String code, Token token) {
        String activeProvider = SecurityConstants.AUTH_PROVIDER.get();
        Identity identity = token == null ? null : token.getUserIdentity();
        if (StringUtils.isAnyBlank(code, activeProvider)
                || token.getAuthenticatedAsAccountId() == null
                || identity == null
                || StringUtils.isAnyBlank(identity.getExternalIdType(), identity.getExternalId())) {
            return false;
        }
        return authDao.consumeProviderSwitchTicket(ticketKey(code), activeProvider,
                token.getAuthenticatedAsAccountId(), identity.getExternalIdType(),
                identity.getExternalId()) != null;
    }

    void cancel(String code) {
        if (StringUtils.isNotBlank(code)) {
            authDao.cancelProviderSwitchTicket(ticketKey(code));
        }
    }

    private String ticketKey(String code) {
        return IdentityLinkKey.create("provider-switch", "code", code);
    }

    private long ttlSeconds() {
        Long configured = TTL_SECONDS.get();
        long value = configured == null ? 120L : configured.longValue();
        return Math.max(MIN_TTL_SECONDS, Math.min(MAX_TTL_SECONDS, value));
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
