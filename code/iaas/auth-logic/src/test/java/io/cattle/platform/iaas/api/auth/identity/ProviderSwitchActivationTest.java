package io.cattle.platform.iaas.api.auth.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;

import java.util.Collections;
import java.util.Set;

import org.junit.Test;

public class ProviderSwitchActivationTest {

    @Test
    public void recoveryTicketClaimsOnlyTheProvedLoginAndStableAccount() {
        AccountRecord account = new AccountRecord();
        account.setId(1L);
        account.setName("Administrator");
        Identity provedLogin = new Identity("oidc_user", "issuer|subject", "Administrator",
                null, null, "administrator", true);
        ProviderSwitchTokenService service = new ProviderSwitchTokenService();

        Set<Identity> identities = service.recoveryIdentities(account, provedLogin);

        assertEquals(2, identities.size());
        assertTrue(identities.contains(provedLogin));
        assertTrue(identities.contains(new Identity(ProjectConstants.RANCHER_ID, "1")));
    }

    @Test
    public void matchingOneUseActivationProofMarksTheFreshProviderLogin() {
        Identity identity = new Identity("oidc_user", "issuer|subject", "Administrator",
                null, null, "administrator", true);
        Token token = new Token("jwt", "1a1", identity,
                Collections.singletonList(identity), "admin", 1L);
        ProviderSwitchTokenService service = new ProviderSwitchTokenService();
        service.ticketService = new ProviderSwitchTicketService() {
            @Override
            boolean consumeForActivation(String code, Token candidate) {
                assertEquals("one-use-code", code);
                assertSame(token, candidate);
                return true;
            }
        };

        service.authorizeActivation("one-use-code", token);

        assertEquals("providerSwitchActivation", token.getLoginMethod());
    }

    @Test(expected = ClientVisibleException.class)
    public void mismatchedActivationProofCannotBypassAuthenticationPolicy() {
        Identity identity = new Identity("oidc_user", "issuer|unexpected", "Other user",
                null, null, "other", true);
        Token token = new Token("jwt", "1a2", identity,
                Collections.singletonList(identity), "admin", 2L);
        ProviderSwitchTokenService service = new ProviderSwitchTokenService();
        service.ticketService = new ProviderSwitchTicketService() {
            @Override
            boolean consumeForActivation(String code, Token candidate) {
                return false;
            }
        };

        service.authorizeActivation("wrong-code", token);
    }
}
