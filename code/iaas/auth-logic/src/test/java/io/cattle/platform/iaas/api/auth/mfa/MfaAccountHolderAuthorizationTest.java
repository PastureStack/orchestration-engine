package io.cattle.platform.iaas.api.auth.mfa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import org.junit.Test;

public class MfaAccountHolderAuthorizationTest {

    @Test
    public void accountHolderCanEnrollOwnFactor() {
        AccountRecord account = account(1L, AccountConstants.USER_KIND);
        MfaResourceManager manager = new MfaResourceManager();

        manager.requireAccountHolder(account, account);

        assertTrue(manager.requiresAccountHolder("beginTotpEnrollment"));
        assertTrue(manager.requiresAccountHolder("confirmTotpEnrollment"));
        assertTrue(manager.requiresAccountHolder("beginPasskeyEnrollment"));
        assertTrue(manager.requiresAccountHolder("confirmPasskeyEnrollment"));
        assertTrue(manager.requiresAccountHolder("regenerateRecoveryCodes"));
        assertTrue(manager.requiresAccountHolder("beginRecoveryEmailEnrollment"));
        assertTrue(manager.requiresAccountHolder("confirmRecoveryEmailEnrollment"));
    }

    @Test
    public void administratorCannotEnrollFactorForAnotherAccount() {
        AccountRecord administrator = account(1L, AccountConstants.ADMIN_KIND);
        AccountRecord accountHolder = account(2L, AccountConstants.USER_KIND);

        try {
            new MfaResourceManager().requireAccountHolder(administrator, accountHolder);
            fail("An administrator must not enroll a factor for another account.");
        } catch (ClientVisibleException expected) {
            assertEquals(ResponseCodes.FORBIDDEN, expected.getStatus());
            assertEquals("MfaAccountHolderRequired", expected.getCode());
        }
    }

    @Test
    public void administratorMaintenanceOperationsRemainAvailable() {
        MfaResourceManager manager = new MfaResourceManager();

        assertFalse(manager.requiresAccountHolder("revokeFactor"));
        assertFalse(manager.requiresAccountHolder("revokeRecoveryEmail"));
        assertFalse(manager.requiresAccountHolder("revokeAllFactors"));
    }

    private AccountRecord account(Long id, String kind) {
        AccountRecord account = new AccountRecord();
        account.setId(id);
        account.setKind(kind);
        account.setState("active");
        return account;
    }
}
