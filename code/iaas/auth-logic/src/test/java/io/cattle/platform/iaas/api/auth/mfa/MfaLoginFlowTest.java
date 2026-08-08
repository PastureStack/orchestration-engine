package io.cattle.platform.iaas.api.auth.mfa;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.cattle.platform.iaas.api.auth.identity.Token;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockCallbackWithException;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.provider.LockProvider;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.util.TransformationService;
import io.github.ibuildthecloud.gdapi.model.Transformer;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.codec.binary.Base32;
import org.junit.Test;

public class MfaLoginFlowTest {

    private static final long NOW = 2_000_000_000_000L;

    @Test
    public void gatesSessionUntilTotpThenRejectsChallengeReplay() {
        Account account = account(42L);
        InMemoryMfaDao dao = new InMemoryMfaDao();
        String secret = new Base32().encodeToString(
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        Map<String, Object> factorData = new HashMap<>();
        factorData.put("lastUsedStep", -1L);
        dao.create(account.getId(), CredentialConstants.KIND_MFA_TOTP, "totp-factor",
                "encrypt:" + secret, factorData);

        MfaService service = service(account, dao);
        Identity identity = new Identity("local", "42", "Administrator",
                null, null, "admin", true);
        Token primary = new Token("protected-primary-token", "1a42", identity,
                Arrays.asList(identity), AccountConstants.ADMIN_KIND, account.getId());
        primary.setAuthProvider("localAuthConfig");
        primary.setLoginMethod("primary");
        primary.setOriginalLogin("admin");

        Token challenge = service.beginLogin(primary);
        assertTrue(challenge.getMfaRequired());
        assertNotNull(challenge.getMfaChallengeId());
        assertEquals(Arrays.asList(MfaService.METHOD_TOTP), challenge.getMfaMethods());
        assertEquals(null, challenge.getJwt());

        long step = (NOW / 1000L) / TotpService.PERIOD_SECONDS;
        String code = TotpService.calculate(
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII),
                step, TotpService.DIGITS);
        Map<String, Object> input = new HashMap<>();
        input.put("code", challenge.getMfaChallengeId());
        input.put("mfaMethod", MfaService.METHOD_TOTP);
        input.put("mfaCode", code);
        Token completed = service.completeLogin(input);

        assertFalse(completed.getMfaRequired());
        assertEquals("protected-primary-token", completed.getJwt());
        assertEquals("primary+mfa", completed.getLoginMethod());
        assertEquals(step, dao.findActive(
                CredentialConstants.KIND_MFA_TOTP, "totp-factor").getData().get("lastUsedStep"));

        boolean replayRejected = false;
        try {
            service.completeLogin(input);
        } catch (ClientVisibleException expected) {
            replayRejected = true;
        }
        assertTrue(replayRejected);
    }

    @Test
    public void boundsPendingLoginChallengesPerAccount() {
        Account account = account(42L);
        InMemoryMfaDao dao = new InMemoryMfaDao();
        String secret = new Base32().encodeToString(
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        dao.create(account.getId(), CredentialConstants.KIND_MFA_TOTP, "totp-factor",
                "encrypt:" + secret, new HashMap<String, Object>());
        MfaService service = service(account, dao);
        Identity identity = new Identity("local", "42", "Administrator",
                null, null, "admin", true);
        Token primary = new Token("protected-primary-token", "1a42", identity,
                Arrays.asList(identity), AccountConstants.ADMIN_KIND, account.getId());
        primary.setAuthProvider("localAuthConfig");
        primary.setLoginMethod("primary");

        Token first = null;
        for (int i = 0; i < 6; i++) {
            Token pending = service.beginLogin(primary);
            if (first == null) {
                first = pending;
            }
        }

        assertEquals(5, dao.listActive(account.getId(),
                CredentialConstants.KIND_MFA_LOGIN_CHALLENGE).size());
        Map<String, Object> replay = new HashMap<>();
        replay.put("code", first.getMfaChallengeId());
        replay.put("mfaMethod", MfaService.METHOD_TOTP);
        replay.put("mfaCode", "000000");
        boolean supersededRejected = false;
        try {
            service.completeLogin(replay);
        } catch (ClientVisibleException expected) {
            supersededRejected = true;
        }
        assertTrue(supersededRejected);
    }

    @Test
    public void newLoginDoesNotResetAccountThrottle() {
        Account account = account(42L);
        InMemoryMfaDao dao = new InMemoryMfaDao();
        String secret = new Base32().encodeToString(
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        dao.create(account.getId(), CredentialConstants.KIND_MFA_TOTP, "totp-factor",
                "encrypt:" + secret, new HashMap<String, Object>());
        MfaService service = service(account, dao);
        Identity identity = new Identity("local", "42", "Administrator",
                null, null, "admin", true);
        Token primary = new Token("protected-primary-token", "1a42", identity,
                Arrays.asList(identity), AccountConstants.ADMIN_KIND, account.getId());
        primary.setAuthProvider("localAuthConfig");
        primary.setLoginMethod("primary");

        String finalFailureCode = null;
        for (int round = 0; round < 2; round++) {
            Token challenge = service.beginLogin(primary);
            for (int attempt = 0; attempt < 5; attempt++) {
                Map<String, Object> input = new HashMap<>();
                input.put("code", challenge.getMfaChallengeId());
                input.put("mfaMethod", MfaService.METHOD_TOTP);
                input.put("mfaCode", "invalid");
                try {
                    service.completeLogin(input);
                } catch (ClientVisibleException expected) {
                    finalFailureCode = expected.getCode();
                }
            }
        }
        assertEquals("MfaTemporarilyLocked", finalFailureCode);

        try {
            service.beginLogin(primary);
        } catch (ClientVisibleException expected) {
            assertEquals("MfaTemporarilyLocked", expected.getCode());
            return;
        }
        throw new AssertionError("A new login challenge reset the account-level failure counter");
    }

    @Test
    public void trustsOnlyFreshSignedFederatedMfaEvidenceWhenEnabled() {
        Account account = account(42L);
        InMemoryMfaDao dao = new InMemoryMfaDao();
        dao.create(account.getId(), CredentialConstants.KIND_MFA_TOTP, "totp-factor",
                "encrypt:unused", new HashMap<String, Object>());
        MfaService service = service(account, dao);
        service.policyService = new MfaPolicyService() {
            @Override
            public MfaPolicy getPolicy() {
                return new MfaPolicy("requiredAdmins", 5, null, null,
                        "PastureStack", "PastureStack", 10, 300, 300,
                        MfaPolicy.FEDERATED_MFA_TRUSTED_CLAIMS, "mfa", "", 300,
                        MfaPolicy.PASSKEY_COUNTER_RISK_AWARE, "zh-tw");
            }
        };
        service.attemptService.policyService = service.policyService;
        Identity identity = new Identity("oidc", "subject", "Administrator",
                null, null, "admin", true);
        Token primary = new Token("protected-primary-token", "1a42", identity,
                Arrays.asList(identity), AccountConstants.ADMIN_KIND, account.getId());
        primary.setAuthProvider("oidcconfig");
        primary.setLoginMethod("primary");
        primary.setFederatedAuthenticationMethods(Arrays.asList("pwd", "mfa"));
        primary.setFederatedAuthenticationContext("urn:example:aal2");
        primary.setFederatedAuthenticatedAt(NOW / 1000L - 60L);
        primary.setFederatedAuthenticationIssuer("https://identity.example");

        Token accepted = service.beginLogin(primary);
        assertEquals("primary+federatedMfa", accepted.getLoginMethod());
        assertFalse(Boolean.TRUE.equals(accepted.getMfaRequired()));

        primary.setLoginMethod("primary");
        primary.setFederatedAuthenticatedAt(NOW / 1000L - 301L);
        Token stale = service.beginLogin(primary);
        assertTrue(stale.getMfaRequired());
    }

    @Test
    public void emergencyLocalAdministratorAlwaysCompletesMfa() {
        Account account = account(42L);
        InMemoryMfaDao dao = new InMemoryMfaDao();
        MfaService service = service(account, dao);
        Identity identity = new Identity("local", "42", "Administrator",
                null, null, "admin", true);
        Token recovery = new Token("protected-local-recovery-token", "1a42", identity,
                Arrays.asList(identity), AccountConstants.ADMIN_KIND, account.getId());
        recovery.setAuthProvider("oidcconfig");
        recovery.setLoginMethod("localRecovery");

        Token challenge = service.beginLogin(recovery);

        assertTrue(challenge.getMfaRequired());
        assertEquals(Arrays.asList(MfaService.METHOD_TOTP_ENROLLMENT),
                challenge.getMfaMethods());
        assertEquals(null, challenge.getJwt());
    }

    @Test
    public void providerSwitchTicketDoesNotBypassRegisteredFactor() {
        Account account = account(42L);
        InMemoryMfaDao dao = new InMemoryMfaDao();
        dao.create(account.getId(), CredentialConstants.KIND_MFA_TOTP, "totp-factor",
                "encrypt:unused", new HashMap<String, Object>());
        MfaService service = service(account, dao);
        Identity identity = new Identity("local", "42", "Administrator",
                null, null, "admin", true);
        Token providerSwitch = new Token("protected-provider-switch-token", "1a42", identity,
                Arrays.asList(identity), AccountConstants.ADMIN_KIND, account.getId());
        providerSwitch.setAuthProvider("localAuthConfig");
        providerSwitch.setLoginMethod("providerSwitchRecovery");

        Token challenge = service.beginLogin(providerSwitch);

        assertTrue(challenge.getMfaRequired());
        assertEquals(Arrays.asList(MfaService.METHOD_TOTP), challenge.getMfaMethods());
        assertEquals(null, challenge.getJwt());
    }

    @Test
    public void securityConfirmationIsShortLivedAndSingleUse() {
        Account account = account(42L);
        InMemoryMfaDao dao = new InMemoryMfaDao();
        String secret = new Base32().encodeToString(
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII));
        dao.create(account.getId(), CredentialConstants.KIND_MFA_TOTP, "totp-factor",
                "encrypt:" + secret, new HashMap<String, Object>());
        MfaService service = service(account, dao);

        Map<String, Object> challenge = service.beginSecurityConfirmation(account);
        long step = (NOW / 1000L) / TotpService.PERIOD_SECONDS;
        String code = TotpService.calculate(
                "12345678901234567890".getBytes(StandardCharsets.US_ASCII),
                step, TotpService.DIGITS);
        Map<String, Object> result = service.finishSecurityConfirmation(account,
                String.valueOf(challenge.get("challengeId")), MfaService.METHOD_TOTP,
                code, null, null);
        String ticket = String.valueOf(result.get("securityConfirmation"));
        assertNotNull(ticket);

        service.consumeSecurityConfirmation(account, ticket);
        try {
            service.consumeSecurityConfirmation(account, ticket);
        } catch (ClientVisibleException expected) {
            assertEquals("MfaReauthenticationRequired", expected.getCode());
            return;
        }
        throw new AssertionError("A security confirmation ticket was accepted more than once");
    }

    private MfaService service(final Account account, InMemoryMfaDao dao) {
        MfaService service = new MfaService() {
            @Override
            long now() {
                return NOW;
            }
        };
        service.mfaDao = dao;
        service.policyService = new MfaPolicyService() {
            @Override
            public MfaPolicy getPolicy() {
                return new MfaPolicy("optional", 5, null, null,
                        "PastureStack", "PastureStack");
            }
        };
        service.totpService = new TotpService();
        service.webAuthnService = new WebAuthnService();
        service.emailRecoveryService = new EmailRecoveryService() {
            @Override
            public boolean isAvailable(Account ignored) {
                return false;
            }
        };
        service.transformationService = new PlainTransformationService();
        service.jsonMapper = new JacksonJsonMapper();
        service.lockManager = new ImmediateLockManager();
        service.authDao = proxy(AuthDao.class, (method, args) ->
                "getAccountById".equals(method.getName()) ? account : defaultValue(method.getReturnType()));
        service.accountDao = proxy(AccountDao.class, (method, args) ->
                "isActiveAccount".equals(method.getName()) || defaultBoolean(method.getReturnType()));
        service.authTokenDao = proxy(AuthTokenDao.class,
                (method, args) -> defaultValue(method.getReturnType()));
        MfaAttemptService attempts = new MfaAttemptService() {
            @Override
            long now() {
                return NOW;
            }
        };
        attempts.mfaDao = dao;
        attempts.policyService = service.policyService;
        attempts.lockManager = service.lockManager;
        service.attemptService = attempts;
        return service;
    }

    private static Account account(long id) {
        return proxy(Account.class, (method, args) -> {
            if ("getId".equals(method.getName())) {
                return id;
            }
            if ("getKind".equals(method.getName())) {
                return AccountConstants.ADMIN_KIND;
            }
            if ("getName".equals(method.getName())) {
                return "Administrator";
            }
            if ("getState".equals(method.getName())) {
                return CommonStatesConstants.ACTIVE;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private static class InMemoryMfaDao implements MfaDao {
        private final List<CredentialRecord> values = new ArrayList<>();
        private long nextId = 1;

        @Override
        public Credential create(long accountId, String kind, String publicValue, String secretValue,
                                 Map<String, Object> data) {
            CredentialRecord value = new CredentialRecord();
            value.setId(nextId++);
            value.setAccountId(accountId);
            value.setKind(kind);
            value.setPublicValue(publicValue);
            value.setSecretValue(secretValue);
            value.setState(CommonStatesConstants.ACTIVE);
            value.setData(data == null ? new HashMap<String, Object>() : new HashMap<>(data));
            values.add(value);
            return value;
        }

        @Override
        public Credential findActive(String kind, String publicValue) {
            for (CredentialRecord value : values) {
                if (kind.equals(value.getKind()) && publicValue.equals(value.getPublicValue())
                        && CommonStatesConstants.ACTIVE.equals(value.getState())) {
                    return value;
                }
            }
            return null;
        }

        @Override
        public List<? extends Credential> listActive(long accountId, String... kinds) {
            List<Credential> result = new ArrayList<>();
            List<String> accepted = Arrays.asList(kinds);
            for (CredentialRecord value : values) {
                if (accountId == value.getAccountId() && accepted.contains(value.getKind())
                        && CommonStatesConstants.ACTIVE.equals(value.getState())) {
                    result.add(value);
                }
            }
            return result;
        }

        @Override
        public List<? extends Credential> listActiveByKind(String kind) {
            List<Credential> result = new ArrayList<>();
            for (CredentialRecord value : values) {
                if (kind.equals(value.getKind()) && CommonStatesConstants.ACTIVE.equals(value.getState())) {
                    result.add(value);
                }
            }
            return result;
        }

        @Override
        public Credential save(Credential credential, String secretValue, Map<String, Object> data) {
            if (secretValue != null) {
                credential.setSecretValue(secretValue);
            }
            if (data != null) {
                credential.setData(new HashMap<>(data));
            }
            return credential;
        }

        @Override
        public void deactivate(Credential credential, String reason) {
            credential.setState(CommonStatesConstants.INACTIVE);
        }
    }

    private static class PlainTransformationService implements TransformationService {
        private Map<String, Transformer> transformers = new HashMap<>();

        @Override
        public String transform(String value, String method) {
            return method + ":" + value;
        }

        @Override
        public String untransform(String value) {
            return value == null ? null : value.substring(value.indexOf(':') + 1);
        }

        @Override
        public boolean compare(String plainText, String transformed) {
            return plainText != null && plainText.equals(untransform(transformed));
        }

        @Override
        public void setTransformers(Map<String, Transformer> transformers) {
            this.transformers = transformers;
        }

        @Override
        public Map<String, Transformer> getTransformers() {
            return transformers;
        }
    }

    private static class ImmediateLockManager implements LockManager {
        @Override
        public <T, E extends Throwable> T lock(LockDefinition lockDef,
                                               LockCallbackWithException<T, E> callback,
                                               Class<E> clz) throws E {
            return callback.doWithLock();
        }

        @Override
        public <T> T lock(LockDefinition lockDef, LockCallback<T> callback) {
            return callback.doWithLock();
        }

        @Override
        public <T> T tryLock(LockDefinition lockDef, LockCallback<T> callback) {
            return callback.doWithLock();
        }

        @Override
        public <T, E extends Throwable> T tryLock(LockDefinition lockDef,
                                                  LockCallbackWithException<T, E> callback,
                                                  Class<E> clz) throws E {
            return callback.doWithLock();
        }

        @Override
        public LockProvider getLockProvider() {
            return null;
        }
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type},
                (proxy, method, args) -> handler.invoke(method, args)));
    }

    private static boolean defaultBoolean(Class<?> type) {
        return type == boolean.class;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == int.class) {
            return 0;
        }
        return null;
    }

    private interface Handler {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }
}
