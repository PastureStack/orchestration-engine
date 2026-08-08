package io.cattle.platform.iaas.api.auth.identity;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.iaas.api.auth.AbstractTokenUtil;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.token.TokenService;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

public class ProviderSwitchTokenService {

    private static final String EXTERNAL_TOKEN_TYPE = "externaljwt";

    @Inject
    ProviderSwitchTicketService ticketService;
    @Inject
    TokenService tokenService;
    @Inject
    ObjectManager objectManager;

    Token consume(String code) {
        ProviderSwitchTicket ticket = ticketService.consume(code);
        if (ticket == null) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED, "InvalidProviderSwitchTicket",
                    "The provider-switch recovery ticket is invalid, expired, or already used.", null);
        }

        Account account = ticket.getAccount();
        Identity user = ticket.getIdentity();
        Set<Identity> identities = recoveryIdentities(account, user);

        Map<String, Object> claims = new HashMap<>();
        claims.put(AbstractTokenUtil.TOKEN, EXTERNAL_TOKEN_TYPE);
        claims.put(AbstractTokenUtil.ACCOUNT_ID, user.getExternalId());
        claims.put(AbstractTokenUtil.PRINCIPAL_ACCOUNT_ID, String.valueOf(account.getId()));
        claims.put(AbstractTokenUtil.ID_LIST, identityIds(identities));
        claims.put(AbstractTokenUtil.USER_IDENTITY, user);
        claims.put(AbstractTokenUtil.USER_TYPE, account.getKind());
        claims.put("originalLogin", user.getLogin());

        Date expiry = new Date(System.currentTimeMillis() + SecurityConstants.TOKEN_EXPIRY_MILLIS.get());
        String jwt = tokenService.generateEncryptedToken(claims, expiry);
        String accountId = String.valueOf(ApiContext.getContext().getIdFormatter()
                .formatId(objectManager.getType(Account.class), account.getId()));
        Token token = new Token(jwt, accountId, user, new ArrayList<>(identities), account.getKind(),
                account.getId());
        token.setAuthProvider(ticket.getProvider());
        token.setLoginMethod("providerSwitchRecovery");
        return token;
    }

    Set<Identity> recoveryIdentities(Account account, Identity user) {
        Set<Identity> identities = new LinkedHashSet<>();
        identities.add(user);
        // A recovery ticket proves exactly one login method.  Including links
        // from inactive providers makes the identity transformation fail and
        // would also grant a recovery session more identity claims than the
        // ticket actually proved.
        identities.add(new Identity(io.cattle.platform.core.constants.ProjectConstants.RANCHER_ID,
                String.valueOf(account.getId()), account.getName(), null, null, null, true));
        return identities;
    }

    void authorizeActivation(String code, Token token) {
        if (!ticketService.consumeForActivation(code, token)) {
            throw new ClientVisibleException(ResponseCodes.UNAUTHORIZED,
                    "InvalidProviderSwitchActivation",
                    "The provider-switch activation proof is invalid, expired, already used, "
                            + "or does not match the verified account and identity.",
                    null);
        }
        token.setLoginMethod("providerSwitchActivation");
    }

    private ArrayList<String> identityIds(Set<Identity> identities) {
        ArrayList<String> result = new ArrayList<>();
        for (Identity identity : identities) {
            result.add(identity.getId());
        }
        return result;
    }
}
