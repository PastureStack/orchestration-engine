package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;

import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.iaas.api.auth.mfa.MfaService;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ApiKeyPolicyManagementServiceTest {
    private final ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();

    @Test public void createDerivesOwnerAndStoresFullWithoutImplicitExpiry() {
        Fixture fixture = new Fixture();
        Map<String, Object> input = input(full(), null);
        input.put("accountId", "foreign-project"); input.put("name", "");
        CredentialRecord created = new CredentialRecord();
        fixture.service.prepareCreate(created, request(input));
        assertEquals(Long.valueOf(42), created.getAccountId());
        assertEquals(CredentialConstants.KIND_API_KEY, created.getKind());
        assertEquals(ApiKeyPolicy.Mode.FULL, codec.read(created).getMode());
        assertNull(codec.read(created).getExpiresAt());
        assertEquals(1, codec.revision(created));
        assertEquals(42L, input.get("accountId"));
        assertEquals(created.getData(), input.get("data"));
    }

    @Test public void nativeV1AndV2CreatePreserveEnvironmentPrincipalAndOptionalName() {
        for (String version : List.of("v1", "v2-beta")) {
            Fixture fixture = new Fixture();
            Map<String, Object> input = new HashMap<>(Map.of("type", "apiKey", "kind", "apiKey", "accountId", "1a77"));
            Map<String, Object> original = new HashMap<>(input);
            CredentialRecord created = new CredentialRecord();
            created.setAccountId(77L); created.setKind(CredentialConstants.KIND_API_KEY);
            ApiRequest request = request(input); request.setVersion(version); request.setType("apiKey");
            fixture.service.prepareCreate(created, request);
            assertEquals(version, Long.valueOf(77), created.getAccountId());
            assertEquals(CredentialConstants.KIND_API_KEY, created.getKind());
            assertNull(codec.read(created)); assertEquals(0, codec.revision(created));
            assertEquals(original, input); assertNull(created.getName());
        }
    }

    @Test public void legacyCreateCannotSmuggleRestrictedKindOrServerPolicyData() {
        Fixture fixture = new Fixture();
        CredentialRecord restricted = new CredentialRecord(); restricted.setKind(CredentialConstants.KIND_API_KEY_RESTRICTED);
        assertEquals("ApiKeyPolicyInvalid", rejected(() -> fixture.service.prepareCreate(restricted, request(new HashMap<>(Map.of("type", "apiKey"))))));
        assertEquals("ApiKeyPolicyInvalid", rejected(() -> fixture.service.prepareCreate(new CredentialRecord(), request(new HashMap<>(Map.of("kind", "apiKeyRestricted"))))));
        assertEquals("ApiKeyPolicyInvalid", rejected(() -> fixture.service.prepareCreate(new CredentialRecord(), request(new HashMap<>(Map.of(ApiKeyPolicyCodec.REVISION, 1L))))));
        assertEquals("ApiKeyPolicyInvalid", rejected(() -> fixture.service.prepareCreate(new CredentialRecord(), request(new HashMap<>(Map.of("type", "foreignSchema"))))));
    }

    @Test public void closedAndExpiryUseTheOldEngineRejectingKind() {
        Fixture fixture = new Fixture();
        CredentialRecord closed = new CredentialRecord();
        fixture.service.prepareCreate(closed, request(input(closed(), null)));
        assertEquals(CredentialConstants.KIND_API_KEY_RESTRICTED, closed.getKind());
        CredentialRecord expiring = new CredentialRecord();
        ApiKeyPolicy expiringFull = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW,
                Instant.parse("2099-01-01T00:00:00Z"), List.of());
        fixture.service.prepareCreate(expiring, request(input(expiringFull, null)));
        assertEquals(CredentialConstants.KIND_API_KEY_RESTRICTED, expiring.getKind());
    }

    @Test public void rawDataAndOwnerMutationAreRejectedBeforeAnyWrite() {
        Fixture fixture = new Fixture();
        Map<String, Object> create = new HashMap<>(Map.of("data", Map.of(ApiKeyPolicyCodec.OWNER, 999L)));
        assertEquals("ApiKeyPolicyInvalid", rejected(() -> fixture.service.prepareCreate(new CredentialRecord(), request(create))));
        Map<String, Object> edit = input(closed(), 0L);
        edit.put("accountId", 999L);
        assertEquals("ApiKeyPolicyInvalid", rejected(() -> fixture.service.update("apiKey", "12", request(edit), fixture.next)));
        assertEquals(0, fixture.writes.get());
    }

    @Test public void revisionConflictAndDoubleSubmitDoNotWriteAgain() {
        Fixture fixture = new Fixture();
        Map<String, Object> edit = input(closed(), 0L);
        fixture.service.update("apiKey", "12", request(edit), fixture.next);
        assertEquals(1, codec.revision(fixture.key));
        assertEquals(1, fixture.writes.get());
        assertEquals("ApiKeyPolicyRevisionConflict", rejected(() -> fixture.service.update("apiKey", "12", request(edit), fixture.next)));
        assertEquals(1, fixture.writes.get());
        assertEquals(Long.valueOf(77), fixture.key.getAccountId());
    }

    @Test public void basicEditDoesNotMigrateLegacyPrincipalPolicyOrExpiry() {
        Fixture fixture = new Fixture();
        fixture.service.update("apiKey", "12", request(new HashMap<>(Map.of("name", "new-name"))), fixture.next);
        assertEquals("new-name", fixture.key.getName());
        assertEquals("original-description", fixture.key.getDescription());
        assertNull(codec.read(fixture.key));
        assertEquals(Long.valueOf(77), fixture.key.getAccountId());
    }

    @Test public void revokedAndRemovedKeysCannotBeRenewedOrReactivated() {
        Fixture fixture = new Fixture();
        fixture.key.setState("inactive");
        assertEquals("ApiKeyRevoked", rejected(() -> fixture.service.update("apiKey", "12", request(input(full(), 0L)), fixture.next)));
        fixture.key.setState("active");
        fixture.key.setRemoved(new Date());
        assertEquals("ApiKeyRevoked", rejected(() -> fixture.service.update("apiKey", "12", request(input(full(), 0L)), fixture.next)));
        assertEquals(0, fixture.writes.get());
    }

    @Test public void wideningConsumesOneActorPolicyRevisionBoundConfirmation() {
        Fixture fixture = new Fixture();
        fixture.key.setData(codec.store(fixture.key, closed(), 5));
        fixture.key.setKind(CredentialConstants.KIND_API_KEY_RESTRICTED);
        Map<String, Object> edit = input(full(), 5L);
        edit.put("securityConfirmation", "ticket");
        fixture.service.update("apiKeyRestricted", "12", request(edit), fixture.next);
        assertEquals(1, fixture.confirmations.get());
        assertEquals(6, codec.revision(fixture.key));
        assertEquals(CredentialConstants.KIND_API_KEY_RESTRICTED, fixture.key.getKind());
        assertFalse(edit.containsKey("securityConfirmation"));
        assertFalse(fixture.key.getData().containsKey("securityConfirmation"));
        assertEquals("ApiKeyPolicyRevisionConflict", rejected(() -> fixture.service.update("apiKeyRestricted", "12", request(edit), fixture.next)));
        assertEquals(1, fixture.confirmations.get());
    }

    @Test public void digestIsCanonicalAndBindsActorKeyRevisionAndExpiry() {
        Fixture fixture = new Fixture();
        String base = fixture.service.digest(42, 12L, 1, full());
        assertEquals(64, base.length());
        assertNotEquals(base, fixture.service.digest(43, 12L, 1, full()));
        assertNotEquals(base, fixture.service.digest(42, 13L, 1, full()));
        assertNotEquals(base, fixture.service.digest(42, 12L, 2, full()));
        assertNotEquals(base, fixture.service.digest(42, 12L, 1, closed()));
    }

    @Test public void expiryExtensionRequiresConfirmationAndPastExpiryIsRejected() {
        Fixture fixture = new Fixture();
        ApiKeyPolicy expiring = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW,
                Instant.parse("2098-01-01T00:00:00Z"), List.of());
        assertTrue(fixture.service.requiresConfirmation(expiring, full()));
        ApiKeyPolicy shorter = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW,
                Instant.parse("2097-01-01T00:00:00Z"), List.of());
        assertFalse(fixture.service.requiresConfirmation(expiring, shorter));
        ApiKeyPolicy expired = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW,
                Instant.EPOCH, List.of());
        assertEquals("ApiKeyExpiryInvalid", rejected(() -> fixture.service.prepareCreate(new CredentialRecord(), request(input(expired, null)))));
    }

    private Map<String, Object> input(ApiKeyPolicy policy, Long revision) {
        Map<String, Object> input = new HashMap<>();
        input.put(ApiKeyPolicyCodec.POLICY, codec.encode(policy));
        if (revision != null) input.put(ApiKeyPolicyCodec.REVISION, revision);
        return input;
    }
    private static ApiRequest request(Map<String, Object> input) {
        ApiRequest request = new ApiRequest(null, null); request.setRequestObject(input); return request;
    }
    private static String rejected(Runnable action) {
        return assertThrows(ClientVisibleException.class, action::run).getCode();
    }
    private static ApiKeyPolicy full() { return new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()); }
    private static ApiKeyPolicy closed() { return new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of()); }

    private class Fixture {
        final CredentialRecord key = new CredentialRecord();
        final AccountRecord actor = new AccountRecord();
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger confirmations = new AtomicInteger();
        final ApiKeyPolicyManagementService service;
        final ResourceManager next;
        Fixture() {
            key.setId(12L); key.setAccountId(77L); key.setKind(CredentialConstants.KIND_API_KEY); key.setState("active");
            key.setName("original-name"); key.setDescription("original-description");
            actor.setId(42L); actor.setState("active");
            service = new ApiKeyPolicyManagementService() {
                @Override protected Account actor() { return actor; }
                @Override protected void persist(Credential current, Map<String, Object> data, String kind, Map<String, Object> input) {
                    writes.incrementAndGet(); current.setData(data); current.setKind(kind);
                    if (input.containsKey("name")) current.setName((String) input.get("name"));
                    if (input.containsKey("description")) current.setDescription((String) input.get("description"));
                }
            };
            service.jsonMapper = new JacksonJsonMapper();
            service.objectManager = (ObjectManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ObjectManager.class},
                    (proxy, method, args) -> "reload".equals(method.getName()) ? args[0] : null);
            service.lockManager = (LockManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{LockManager.class},
                    (proxy, method, args) -> ((LockCallback<?>) args[1]).doWithLock());
            service.mfaService = new MfaService() {
                @Override public void consumeSecurityConfirmation(Account account, String ticket, String purpose, String digest) {
                    assertEquals(42L, account.getId().longValue()); assertEquals("ticket", ticket);
                    assertEquals(MfaService.PURPOSE_API_KEY_POLICY_UPDATE, purpose);
                    assertEquals(service.digest(42, 12L, 5, full()), digest);
                    confirmations.incrementAndGet();
                }
            };
            next = (ResourceManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResourceManager.class},
                    (proxy, method, args) -> "getById".equals(method.getName()) ? key : null);
        }
    }
}
