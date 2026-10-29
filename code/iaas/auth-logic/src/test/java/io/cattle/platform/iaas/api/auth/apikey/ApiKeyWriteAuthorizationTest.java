package io.cattle.platform.iaas.api.auth.apikey;

import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractApiRequestHandler;
import io.github.ibuildthecloud.gdapi.request.handler.write.DefaultReadWriteApiDelegate;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.After;
import org.junit.Test;

public class ApiKeyWriteAuthorizationTest {
    private static final Instant NOW = Instant.parse("2026-10-21T00:00:00Z");
    private static final ApiKeyPolicy FULL = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of());
    private static final ApiKeyPolicy CUSTOM = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
            List.of(new ApiKeyPolicy.Rule("update", ApiKeyPolicy.Effect.ALLOW,
                    new ApiKeyPolicy.Scope(ApiKeyPolicy.ScopeKind.RESOURCE, "stack", "1st8"), java.util.Set.of("update"))));

    @After public void cleanup() { ApiContext.remove(); }

    @Test public void bridgeDisableDuringManagerWaitRejectsBeforeCommitAndGeneralRemainsUsable() throws Exception {
        Object previous = io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            ApiKeyPolicy bridge = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
            Fixture f = new Fixture(bridge);
            f.request.setType("aiServiceUpgradePlan"); f.request.setMethod("POST"); f.request.setAction("apply");
            assertCode("AiBridgeDisabled", () -> f.run(() -> io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", false)));
            assertEquals("DENY", f.request.getAttribute("apiKey.audit.decision"));
            assertEquals("AiBridgeDisabled", f.request.getAttribute("apiKey.audit.reason"));
            Fixture general = new Fixture(FULL); general.run(() -> { });
            assertEquals("ALLOW", general.request.getAttribute("apiKey.audit.decision"));
        } finally {
            if (previous == null) io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void bridgeWriteRequiresItsPinnedRevisionAtCommit() throws Exception {
        Object previous=io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled",true);
            ApiKeyPolicy bridge=new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,ApiKeyPolicy.Effect.ALLOW,null,List.of(),ApiKeyPolicy.Purpose.AI_BRIDGE);
            for(String expected: new String[] {null,"2","1"}) {
                Fixture f=new Fixture(bridge); f.request.setType("aiServiceDescriptionUpdate"); f.request.setId(null); f.request.setMethod("POST");
                jakarta.servlet.http.HttpServletRequest http=mock(jakarta.servlet.http.HttpServletRequest.class);
                when(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn(expected);
                f.request.setRequestServletContext(new io.github.ibuildthecloud.gdapi.server.model.ApiServletContext(http,null,null));
                if("1".equals(expected)) f.run(()->{});
                else {
                    assertCode("ApiKeyPolicyChanged",()->f.run(()->{}));
                    assertEquals("DENY",f.request.getAttribute("apiKey.audit.decision"));
                }
            }
        } finally {
            if(previous==null) io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled",previous);
        }
    }

    @Test public void fullLegacyAndUpdateOnlyKeepNativeWritesWithoutNewScopeOrReadChecks() throws Exception {
        for (ApiKeyPolicy policy : new ApiKeyPolicy[] { null, FULL, CUSTOM }) {
            Fixture f = new Fixture(policy);
            Policy owner = mock(Policy.class); ApiContext.getContext().setPolicy(owner);
            f.run(() -> { });
            assertEquals(List.of("manager", "lock"), f.events);
            assertEquals("ALLOW", f.request.getAttribute("apiKey.audit.decision"));
            verifyNoInteractions(owner);
        }
    }

    @Test public void completedRevocationRejectsTheWaitingWriteAndUpdatesOnlyCompletionMetadata() {
        for (String state : List.of("inactive", "removed")) {
            Fixture f = new Fixture(CUSTOM);
            assertCode("ApiKeyRevoked", () -> f.run(() -> f.key.setState(state)));
            assertEquals(List.of("manager", "lock"), f.events);
            assertEquals("DENY", f.request.getAttribute("apiKey.audit.decision"));
            assertEquals("ApiKeyRevoked", f.request.getAttribute("apiKey.audit.reason"));
            assertEquals("admission-request", f.request.getAttribute("apiKey.audit.requestId"));
            assertEquals(true, f.request.getAttribute("apiKey.audit.admitted"));
        }
    }

    @Test public void missingRemovedOrNonKeyCredentialCannotCommit() {
        for (String invalid : List.of("missing", "removed", "kind", "owner")) {
            Fixture f = new Fixture(FULL);
            assertCode(invalid.equals("owner") ? "OwnerPermissionDenied" : "ApiKeyRevoked", () -> f.run(() -> {
                switch (invalid) {
                    case "missing" -> f.present = false;
                    case "removed" -> f.key.setRemoved(new Date());
                    case "kind" -> f.key.setKind("password");
                    case "owner" -> f.key.setAccountId(99L);
                }
            }));
        }
    }

    @Test public void changedRevisionRejectsEvenWhenTheNewPolicyIsFull() {
        Fixture f = new Fixture(CUSTOM);
        assertCode("ApiKeyPolicyChanged", () -> f.run(() -> f.store(FULL, 2)));
    }

    @Test public void changedKindWithTheSameRevisionStillFailsClosed() {
        Fixture f = new Fixture(CUSTOM);
        assertCode("ApiKeyPolicyChanged", () -> f.run(() -> f.key.setKind("apiKeyRestricted")));
    }

    @Test public void malformedStoredPolicyFailsClosed() {
        Fixture f = new Fixture(CUSTOM);
        assertCode("ApiKeyPolicyInvalid", () -> f.run(() -> f.key.setData(Map.of(
                ApiKeyPolicyCodec.POLICY, Map.of("mode", "full"), ApiKeyPolicyCodec.REVISION, -1))));
    }

    @Test public void expiryIsCheckedAfterTheManagerWaitForCustomAndFull() {
        for (ApiKeyPolicy.Mode mode : List.of(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Mode.FULL)) {
            ApiKeyPolicy expiring = new ApiKeyPolicy(mode, ApiKeyPolicy.Effect.ALLOW, NOW.plusSeconds(30), List.of());
            Fixture f = new Fixture(expiring);
            assertCode("ApiKeyExpired", () -> f.run(() -> f.guard.clock = Clock.fixed(NOW.plusSeconds(30), ZoneOffset.UTC)));
        }
    }

    @Test public void fullAndLegacySelfPolicyRevokeAndDeleteUseTheVerifiedUrlOperand() throws Exception {
        for (ApiKeyPolicy policy : new ApiKeyPolicy[] { null, FULL }) {
            for (String type : List.of("credential", "apiKey", "apiKeyRestricted")) {
                for (String operation : List.of("policy", "deactivate", "delete", "purge")) {
                    Fixture f = new Fixture(policy); f.self(type);
                    f.request.setMethod(operation.equals("delete") ? "DELETE" : operation.equals("deactivate") ? "POST" : "PUT");
                    if (operation.equals("deactivate")) f.request.setAction("deactivate");
                    if (operation.equals("purge")) { f.request.setMethod("POST"); f.request.setAction("purge"); }
                    f.run(() -> {
                        if (operation.equals("policy")) {
                            ApiKeyWriteAuthorization.beforeSelfPolicyUpdate(f.request, f.key);
                            f.store(new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of()), 2);
                            f.key.setKind("apiKeyRestricted");
                            ApiKeyWriteAuthorization.selfPolicyUpdated(f.request, f.key);
                        }
                        else if (operation.equals("purge")) f.present = false;
                        else f.key.setState(operation.equals("delete") ? "removed" : "inactive");
                    });
                    assertEquals(List.of("manager", "lock"), f.events);
                }
            }
        }
    }

    @Test public void selfPolicyManagerRequiresTheOriginalAdmittedCredentialBeforeItsCas() {
        Fixture f = new Fixture(FULL); f.self("apiKey"); f.key.setState("inactive");
        assertCode("ApiKeyRevoked", () -> f.run(() -> {
            ApiKeyWriteAuthorization.beforeSelfPolicyUpdate(f.request, f.key);
            fail("policy CAS must not run");
        }));
        assertEquals(List.of("manager"), f.events);
    }

    @Test public void selfPolicyNeedsTheOwningManagersSuccessfulCasMarker() {
        Fixture f = new Fixture(FULL); f.self("apiKey");
        assertCode("ApiKeyPolicyChanged", () -> f.run(() -> f.store(FULL, 2)));
    }

    @Test public void nativeLowercaseKeyAliasUsesItsCanonicalSchemaAndNeverTheBodyType() throws Exception {
        Fixture f = new Fixture(FULL); f.self("apiKey");
        f.request.setType("apikey");
        when(f.schemas.getSchemaName("apikey")).thenReturn("apiKey");
        f.run(() -> {
            ApiKeyWriteAuthorization.beforeSelfPolicyUpdate(f.request, f.key);
            f.store(FULL, 2);
            ApiKeyWriteAuthorization.selfPolicyUpdated(f.request, f.key);
        });
        assertEquals(List.of("manager", "lock"), f.events);
    }

    @Test public void selfReductionNeedsNoPreWriteRowLockAndOtherActionsReceiveTheNormalGuard() throws Exception {
        Fixture f = new Fixture(FULL); f.self("apiKey");
        f.request.setMethod("POST"); f.request.setAction("deactivate");
        f.key.setState("inactive");
        f.run(() -> f.key.setState("inactive"));
        assertEquals(List.of("manager", "lock"), f.events);
        Fixture activation = new Fixture(FULL); activation.self("apiKey");
        activation.request.setMethod("POST"); activation.request.setAction("activate");
        assertCode("ApiKeyRevoked", () -> activation.run(() -> activation.key.setState("inactive")));
    }

    @Test public void realPolicyManagerSuppliesTheSuccessfulCasMarkerWithoutInvertingLogicalAndRowLocks() throws Exception {
        Fixture f = new Fixture(FULL); f.self("apiKey");
        ApiKeyPolicyManagementService service = policyManager(f);
        f.request.setRequestObject(new HashMap<>(Map.of(ApiKeyPolicyCodec.POLICY,
                f.codec.encode(new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of())),
                ApiKeyPolicyCodec.REVISION, 1L)));
        ResourceManager next = mock(ResourceManager.class);
        when(next.getById(anyString(), anyString(), any())).thenReturn(f.key);
        f.run(() -> service.update("apiKey", "12", f.request, next));
        assertEquals(List.of("manager", "logical", "cas", "lock"), f.events);
        assertEquals(ApiKeyPolicy.Mode.CLOSED, f.codec.read(f.key).getMode());
    }

    @Test public void policyManagerCannotUseAGuessedNewRevisionToBorrowTheAdmittedFullKey() {
        Fixture f = new Fixture(FULL); f.self("apiKey");
        ApiKeyPolicyManagementService service = policyManager(f);
        f.store(FULL, 2);
        f.request.setRequestObject(new HashMap<>(Map.of(ApiKeyPolicyCodec.POLICY, f.codec.encode(FULL),
                ApiKeyPolicyCodec.REVISION, 2L)));
        ResourceManager next = mock(ResourceManager.class);
        when(next.getById(anyString(), anyString(), any())).thenReturn(f.key);
        assertCode("ApiKeyPolicyChanged", () -> f.run(() -> service.update("apiKey", "12", f.request, next)));
        assertEquals(List.of("manager", "logical"), f.events);
    }

    @Test public void selfExceptionDoesNotPermitOwnerRebinding() {
        Fixture f = new Fixture(FULL); f.self("apiKey");
        assertCode("OwnerPermissionDenied", () -> f.run(() -> f.key.setAccountId(99L)));
    }

    @Test public void bodyIdForeignUrlWrongSchemaAndRestrictedKeyCannotBorrowSelfException() {
        for (String variant : List.of("body", "foreign-url", "other-type", "wrong-schema", "custom")) {
            Fixture f = new Fixture(variant.equals("custom") ? CUSTOM : FULL);
            f.request.setRequestObject(Map.of("id", "12", "type", "apiKey"));
            if (!variant.equals("body")) f.self("apiKey");
            switch (variant) {
                case "foreign-url" -> f.request.setId("13");
                case "other-type" -> f.request.setType("stack");
                case "wrong-schema" -> when(f.schemas.getSchemaClass("apiKey", true)).thenReturn((Class) Object.class);
            }
            assertCode("ApiKeyRevoked", () -> f.run(() -> f.key.setState("inactive")));
            assertEquals(List.of("manager", "lock"), f.events);
        }
    }

    @Test public void nonKeyRequestsAndGetDoNotAcquireAnyCredentialLock() throws Exception {
        Fixture f = new Fixture(FULL);
        f.delegate.read(f.request);
        assertEquals(List.of("manager"), f.events);
        f.events.clear();
        ApiRequest ordinary = new ApiRequest(null, f.schemas);
        f.guard.beforeWrite(ordinary); f.guard.beforeCommit(ordinary);
        assertTrue(f.events.isEmpty());
    }

    private static void assertCode(String expected, Checked action) {
        ClientVisibleException denied = assertThrows(ClientVisibleException.class, action::run);
        assertEquals(403, denied.getStatus()); assertEquals(expected, denied.getCode());
    }

    private interface Checked { void run() throws Exception; }

    private ApiKeyPolicyManagementService policyManager(Fixture f) {
        ApiKeyPolicyManagementService service = new ApiKeyPolicyManagementService() {
            @Override protected Account actor() { AccountRecord actor = new AccountRecord(); actor.setId(42L); return actor; }
            @Override protected void persist(Credential current, Map<String, Object> data, String kind, Map<String, Object> input) {
                assertFalse(f.events.contains("lock"));
                f.events.add("cas"); current.setData(data); current.setKind(kind);
            }
        };
        service.objectManager = mock(ObjectManager.class);
        when(service.objectManager.reload(any(Credential.class))).thenAnswer(call -> call.getArgument(0));
        service.lockManager = (LockManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {LockManager.class},
                (proxy, method, args) -> { f.events.add("logical"); return ((LockCallback<?>) args[1]).doWithLock(); });
        return service;
    }

    private static class Fixture {
        final ApiKeyWriteAuthorization guard = new ApiKeyWriteAuthorization();
        final DefaultReadWriteApiDelegate delegate = new DefaultReadWriteApiDelegate();
        final SchemaFactory schemas = mock(SchemaFactory.class);
        final ApiRequest request = new ApiRequest(null, schemas);
        final CredentialRecord key = new CredentialRecord();
        final List<String> events = new ArrayList<>();
        final ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
        boolean present = true;
        Runnable mutation = () -> { };

        Fixture(ApiKeyPolicy policy) {
            key.setId(12L); key.setAccountId(42L); key.setKind("apiKey"); key.setState("active");
            store(policy, policy == null ? 0 : 1);
            request.setType("stack"); request.setId("8"); request.setMethod("PUT");
            ApiContext.newContext().setApiRequest(request); ApiContext.getContext().setIdFormatter(new IdentityFormatter());
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(12, 42, "apiKey", codec.revision(key), policy));
            request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.requestId", "admission-request");
            request.setAttribute("apiKey.audit.admitted", true);
            DSLContext records = DSL.using(SQLDialect.MARIADB);
            guard.setConfiguration(DSL.using(new MockConnection(context -> {
                events.add("lock");
                assertTrue(context.sql().toLowerCase().contains("for update"));
                assertArrayEquals(new Object[] {12L}, context.bindings());
                var result = records.newResult(CREDENTIAL);
                if (present) result.add(key);
                return new MockResult[] {new MockResult(result.size(), result)};
            }), SQLDialect.MARIADB).configuration());
            guard.clock = Clock.fixed(NOW, ZoneOffset.UTC);
            delegate.setWriteAuthorizations(List.of(guard));
            delegate.setHandlers(List.of(new AbstractApiRequestHandler() {
                @Override public void handle(ApiRequest ignored) { events.add("manager"); mutation.run(); }
            }));
        }

        void store(ApiKeyPolicy policy, long revision) {
            key.setData(policy == null ? Map.of() : codec.store(key, policy, revision));
        }

        void self(String type) {
            request.setType(type); request.setId("12");
            when(schemas.getSchemaName(type)).thenReturn(type);
            when(schemas.getSchemaClass(type, true)).thenReturn((Class) Credential.class);
        }

        void run(Runnable mutation) throws Exception { this.mutation = mutation; delegate.write(request); }
    }
}
