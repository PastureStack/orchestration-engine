package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.tables.AccountTable;
import io.cattle.platform.core.model.tables.CredentialTable;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.iaas.api.auth.dao.impl.AuthDaoImpl;
import io.github.ibuildthecloud.gdapi.util.TransformationService;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyVerifiedCredentialDaoTest {
    Object previousTypes;
    @Before public void setup() {
        previousTypes = ArchaiusUtil.getConfiguration().getProperty("account.by.key.credential.types");
        ArchaiusUtil.getConfiguration().setProperty("account.by.key.credential.types", "apiKey,agent");
    }
    @After public void cleanup() {
        if (previousTypes == null) ArchaiusUtil.getConfiguration().clearProperty("account.by.key.credential.types");
        else ArchaiusUtil.getConfiguration().setProperty("account.by.key.credential.types", previousTypes);
    }

    @Test public void inactiveMatchingSecretHasDeniedIdentityAndNonHttpCallerNeverAllowsIt() {
        CredentialRecord credential = credential("apiKey", "inactive");
        AuthDaoImpl dao = dao(credential, true, new AtomicInteger());
        VerifiedApiCredential proof = dao.getVerifiedApiCredential("test-public", "test-secret", transformation(true));
        assertEquals("ApiKeyInactive", proof.denialCode()); assertEquals(77L, proof.context().principalAccountId());
        assertEquals(12L, proof.context().credentialId());
        assertNull(dao.getAccountByKeys("test-public", "test-secret", transformation(true)));
    }
    @Test public void wrongSecretOnRevokedKeyCannotDiscloseOrAttributeAnOwner() {
        CredentialRecord credential = credential("apiKey", "removed"); credential.setRemoved(new Date());
        AtomicInteger accountReads = new AtomicInteger();
        assertNull(dao(credential, true, accountReads).getVerifiedApiCredential("test-public", "wrong-secret", transformation(false)));
        assertEquals(0, accountReads.get());
    }
    @Test public void erasedSecretOnRevokedKeyIsUnidentifiedNotAnExceptionOrOwner() {
        CredentialRecord credential = credential("apiKey", "removed"); credential.setSecretValue(null);
        TransformationService transformation = mock(TransformationService.class); AtomicInteger accountReads = new AtomicInteger();
        assertNull(dao(credential, true, accountReads).getVerifiedApiCredential("test-public", "test-secret", transformation));
        verifyNoInteractions(transformation); assertEquals(0, accountReads.get());
    }
    @Test public void removedActiveKeyAndInactiveOwnerAreDeniedOnlyAfterProof() {
        CredentialRecord removed = credential("apiKey", "active"); removed.setRemoved(new Date());
        assertEquals("ApiKeyRevoked", dao(removed, true, new AtomicInteger()).getVerifiedApiCredential("test-public", "test-secret", transformation(true)).denialCode());
        VerifiedApiCredential inactiveOwner = dao(credential("apiKey", "active"), false, new AtomicInteger())
                .getVerifiedApiCredential("test-public", "test-secret", transformation(true));
        assertEquals("OwnerPermissionDenied", inactiveOwner.denialCode()); assertNull(inactiveOwner.account());
        assertEquals(77L, inactiveOwner.context().principalAccountId());
    }
    @Test public void invalidStoredPolicyIsDenialWithVerifiedIdentityNotAnonymousOrAllow() {
        VerifiedApiCredential invalid = dao(credential("apiKeyRestricted", "active"), true, new AtomicInteger())
                .getVerifiedApiCredential("test-public", "test-secret", transformation(true));
        assertEquals("ApiKeyPolicyInvalid", invalid.denialCode()); assertEquals(12L, invalid.context().credentialId());
    }
    @Test public void inactiveAgentDoesNotAcquireApiKeyIdentityWhileActiveLegacyKeyIsUnchanged() {
        assertNull(dao(credential("agent", "inactive"), true, new AtomicInteger())
                .getVerifiedApiCredential("test-public", "test-secret", transformation(true)));
        VerifiedApiCredential active = dao(credential("apiKey", "active"), true, new AtomicInteger())
                .getVerifiedApiCredential("test-public", "test-secret", transformation(true));
        assertNull(active.denialCode()); assertNull(active.context().policy()); assertEquals(77L, active.account().getId().longValue());
    }

    private static CredentialRecord credential(String kind, String state) {
        CredentialRecord credential = new CredentialRecord(); credential.setId(12L); credential.setAccountId(77L);
        credential.setKind(kind); credential.setState(state); credential.setPublicValue("test-public"); credential.setSecretValue("stored-secret");
        return credential;
    }
    private static TransformationService transformation(boolean matches) {
        TransformationService service = mock(TransformationService.class); when(service.compare(anyString(), eq("stored-secret"))).thenReturn(matches); return service;
    }
    private static AuthDaoImpl dao(CredentialRecord credential, boolean activeOwner, AtomicInteger accountReads) {
        DefaultConfiguration configuration = new DefaultConfiguration(); configuration.set(SQLDialect.MYSQL);
        configuration.set(new MockConnection(context -> {
            String sql = context.sql().toLowerCase(java.util.Locale.ROOT);
            if (sql.contains("credential")) {
                Result<CredentialRecord> rows = DSL.using(SQLDialect.MYSQL).newResult(CredentialTable.CREDENTIAL);
                boolean inactiveLookup = sql.contains("<>");
                if (inactiveLookup) {
                    assertTrue(sql.contains("kind"));
                    assertTrue(List.of(context.bindings()).contains("apiKeyRestricted"));
                    if (!"active".equals(credential.getState()) && ("apiKey".equals(credential.getKind()) || "apiKeyRestricted".equals(credential.getKind()))) rows.add(credential);
                } else if ("active".equals(credential.getState())) rows.add(credential);
                return new MockResult[]{new MockResult(rows.size(), rows)};
            }
            accountReads.incrementAndGet();
            Result<AccountRecord> rows = DSL.using(SQLDialect.MYSQL).newResult(AccountTable.ACCOUNT);
            if (activeOwner) { AccountRecord account = new AccountRecord(); account.setId(77L); account.setState("active"); rows.add(account); }
            return new MockResult[]{new MockResult(rows.size(), rows)};
        }));
        AuthDaoImpl dao = new AuthDaoImpl() { @Override protected List<String> getActiveStates() { return List.of("active"); } };
        dao.setConfiguration(configuration); return dao;
    }
}
