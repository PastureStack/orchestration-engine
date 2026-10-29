package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.docker.api.transform.TransformInspect;
import io.cattle.platform.docker.transform.DockerTransformer;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.framework.secret.SecretsService;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AiCatalogLaunchDelegationTest {
    Object previous; Object previousBridge;
    @Before public void enabled() { previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.catalog.launch.enabled");
        previousBridge = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
        ArchaiusUtil.getConfiguration().setProperty("api.ai.catalog.launch.enabled", true); }
    @After public void clear() { ApiContext.remove();
        if (previousBridge == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previousBridge);
        if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.catalog.launch.enabled");
        else ArchaiusUtil.getConfiguration().setProperty("api.ai.catalog.launch.enabled", previous); }

    @Test public void earlyTransformUsesRealAuthenticationAndFrozenInspectInsteadOfLauncherFullPolicy() throws Exception {
        Fixture f = new Fixture(); ApiRequest request = f.transform(f.proof, f.inspect);
        assertTrue(f.handler.handle(request));
        verify(f.transformer).transform(eq(f.inspect), any());
        assertEquals(42L, ((Policy) ApiContext.getContext().getPolicy()).getAuthenticatedAsAccountId());
        assertEquals(5L, ((Policy) ApiContext.getContext().getPolicy()).getAccountId());
        assertTrue(AiCatalogLaunchAccess.trustedDomain(request)); assertTrue(AiCatalogLaunchDelegation.bootstrap(request));
        assertEquals(0, f.store.writesAfterIssue); // Read-semantic transforms never acquire a mutation ledger entry.
    }

    @Test public void transformSpiPreservesLegacyAndEnforcesVerifiedForgedAndExpiredProofs() throws Exception {
        Fixture legacy = new Fixture(); legacy.authenticated = null;
        assertTrue(legacy.handler.handle(legacy.transform(null, legacy.inspect)));
        verify(legacy.transformer).transform(eq(legacy.inspect), any());
        Fixture verified = new Fixture();
        assertTrue(verified.handler.handle(verified.transform(verified.proof, verified.inspect)));
        assertTrue(AiCatalogLaunchDelegation.bootstrap(ApiContext.getContext().getApiRequest()));
        assertEquals(42L, ((Policy) ApiContext.getContext().getPolicy()).getAuthenticatedAsAccountId());
        for (boolean expired : List.of(false, true)) {
            Fixture denied = new Fixture(); String proof = denied.proof;
            if (expired) denied.plan.put("delegationExpiresAt", 0L);
            else proof = proof.substring(0, proof.length() - 1) + (proof.endsWith("A") ? "B" : "A");
            ApiRequest request = denied.transform(proof, denied.inspect);
            assertEquals(expired ? "CatalogLaunchDelegationExpired" : "CatalogLaunchDelegationDenied",
                    assertThrows(ClientVisibleException.class, () -> denied.handler.handle(request)).getCode());
            verifyNoInteractions(denied.transformer); assertEquals(0, denied.store.writesAfterIssue);
        }
    }

    @Test public void separateReferenceMetadataIsPartOfTheExactDelegationBinding() throws Exception {
        Fixture f = new Fixture(Map.of("db-secret", "1se7"));
        assertFalse(AiCatalogLaunchInput.map(f.values.get("graph")).containsKey("resolvedSecrets"));
        ApiRequest lookup = f.domain("secret", "GET", null, null, Map.of());
        lookup.setRequestParams(Map.of("name", "db-secret"));
        assertEquals(f.plan.get("id"), f.delegation.match(lookup, f.plan, f.values).planId());
        f.values.put("authorizedReferenceIds", Map.of("db-secret", "1se8"));
        f.plan.put("privateEnvelope", f.delegation.privateData.seal(f.plan, f.values));
        assertEquals("CatalogLaunchDelegationDenied", assertThrows(ClientVisibleException.class,
                () -> f.handler.handle(f.transform(f.proof, f.inspect))).getCode());
        verifyNoInteractions(f.transformer); assertEquals(0, f.store.writesAfterIssue);
    }

    @Test public void missingIdentityWrongServiceNoncePlanStackEventOrInspectNeverTransform() throws Exception {
        for (String mutation : List.of("anonymous", "wrong-service", "nonce", "plan", "stack", "event", "inspect", "missing-revision", "project")) {
            Fixture f = new Fixture(); String proof = f.proof; Map<String, Object> inspect = f.inspect;
            if ("anonymous".equals(mutation)) f.authenticated = null;
            if ("wrong-service".equals(mutation)) f.authenticated = f.actor;
            if ("nonce".equals(mutation)) proof = proof.substring(0, proof.length() - 1) + (proof.endsWith("A") ? "B" : "A");
            if ("plan".equals(mutation)) proof = proof.replace("11111111-1111-1111-1111-111111111111", "22222222-2222-2222-2222-222222222222");
            if ("stack".equals(mutation)) f.plan.put("internalStackId", 890L);
            if ("event".equals(mutation)) f.plan.put("delegationEventId", UUID.randomUUID().toString());
            if ("missing-revision".equals(mutation)) f.plan.remove("credentialRevision");
            if ("inspect".equals(mutation)) inspect = Map.of("Name", "/other", "Config", Map.of("Image", "unapproved"));
            ApiRequest request = f.transform(proof, inspect);
            if ("project".equals(mutation)) request.setAttribute(io.cattle.platform.core.constants.ProjectConstants.PROJECT_HEADER, "1a88");
            assertThrows(mutation, ClientVisibleException.class, () -> f.handler.handle(request));
            verifyNoInteractions(f.transformer); assertEquals(mutation, 0, f.store.writesAfterIssue);
        }
    }

    @Test public void liveOriginalCredentialDenialIsNeverReplacedByServiceFullAndOrdinaryTransformRemainsUnchanged() throws Exception {
        for (String reason : List.of("ApiKeyPolicyChanged", "ApiKeyExpired", "ApiKeyRevoked", "OwnerPermissionDenied", "KeyPolicyDenied")) {
            Fixture f = new Fixture(); f.denial = reason;
            var denied = assertThrows(ClientVisibleException.class, () -> f.handler.handle(f.transform(f.proof, f.inspect)));
            assertEquals(reason, denied.getCode()); verifyNoInteractions(f.transformer); assertEquals(0, f.store.writesAfterIssue);
        }
        Fixture ordinary = new Fixture(); ordinary.authenticated = null;
        assertTrue(ordinary.handler.handle(ordinary.transform(null, ordinary.inspect)));
        verify(ordinary.transformer).transform(eq(ordinary.inspect), any());
                assertFalse(AiCatalogLaunchDelegation.bootstrap(ApiContext.getContext().getApiRequest()));
    }

    @Test public void exactGraphClaimsOnceUnknownCannotReplayAndLinkSubsetsCannotShrink() throws Exception {
        Fixture f = new Fixture();
        Map<String, Object> body = Map.of("name", "app", "stackId", "1st889", "launchConfig", Map.of("imageUuid", "docker:approved"));
        ApiRequest create = f.domain("service", "POST", null, null, body);
        var verified = f.delegation.match(create, f.plan, f.values);
        f.delegation.claim(f.plan, verified, create);
        assertEquals(1, f.store.writesAfterIssue);
        assertEquals("CatalogLaunchDispatchUncertain", assertThrows(ClientVisibleException.class,
                () -> f.delegation.claim(f.plan, verified, create)).getCode());
        assertThrows(ClientVisibleException.class, () -> f.delegation.match(f.domain("service", "POST", null, null,
                Map.of("name", "app", "stackId", "1st999", "launchConfig", Map.of("imageUuid", "docker:approved"))), f.plan, f.values));
        Map<String, Object> completed = Map.of("state", "completed", "digest", "first", "linkHashes", List.of(f.graphs.digest(Map.of("serviceId", "1s900"))));
        f.plan.put("ledger", new LinkedHashMap<>(Map.of("links:app", completed)));
        var links = new AiCatalogLaunchDelegation.Verified((String) f.plan.get("id"), "links:app", "service", "app", false);
        assertThrows(ClientVisibleException.class, () -> f.delegation.claim(f.plan, links,
                f.domain("service", "POST", "1s900", "setservicelinks", Map.of("serviceLinks", List.of()))));
    }

    @Test public void missingProofOnlyConstrainsTrustedLauncherDuringItsActiveExecution() throws Exception {
        Fixture f = new Fixture(); ApiRequest mutation = f.domain("service", "POST", null, null, Map.of("name", "app", "stackId", "1st889"));
        assertEquals("CatalogLaunchDelegationRequired", assertThrows(ClientVisibleException.class,
                () -> f.delegation.admit(mutation, f.launcher)).getCode());
        assertNull(f.delegation.admit(mutation, f.actor));
        f.plan.put("status", "succeeded"); f.plan.put("delegationClosed", true);
        assertNull(f.delegation.admit(mutation, f.launcher)); assertNull(f.delegation.admit(mutation, f.actor));
    }

    @Test public void childResourceAndLedgerCommitTogetherBeforeExecutorCanReadTheResponse() throws Exception {
        childTransaction(false);
    }

    @Test public void beforeCommitFailureRollsBackResourceAndLedgerThenRecordsUnknownWithoutReplaying() throws Exception {
        childTransaction(true);
    }

    private void childTransaction(boolean rollback) throws Exception {
        Fixture f = new Fixture();
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                "jdbc:h2:mem:catalog-child-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        var sql = new org.springframework.jdbc.core.JdbcTemplate(source);
        sql.execute("CREATE TABLE ai_catalog_stack_launch_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),document CLOB,"
                + "actor_id BIGINT,account_id BIGINT,stack_id BIGINT UNIQUE,expires_at BIGINT)");
        sql.execute("CREATE TABLE fixture_service(id BIGINT PRIMARY KEY)");
        var store = new AiCatalogLaunchPlanStore(); store.json = new JacksonJsonMapper();
        store.transactions = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        store.setConfiguration(new org.jooq.impl.DefaultConfiguration()
                .set(new org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy(source)).set(org.jooq.SQLDialect.H2));
        f.plan.put("expiresAt", "2030-01-01T00:00:00Z"); store.insert(f.plan); f.delegation.store = store;
        Map<String, Object> body = Map.of("name", "app", "stackId", "1st889", "launchConfig", Map.of("imageUuid", "docker:approved"));
        ApiRequest request = f.transform(f.proof, body); request.setType("service"); request.setId(null); request.setRequestObject(body);
        f.delegation.admit(request, f.launcher);
        var next = mock(io.github.ibuildthecloud.gdapi.request.resource.ResourceManager.class);
        var service = new io.cattle.platform.core.model.tables.records.ServiceRecord(); service.setId(900L); service.setName("app");
        when(next.create("service", request)).thenAnswer(call -> { sql.update("INSERT INTO fixture_service(id) VALUES(900)"); return service; });
        var filter = new AiCatalogLaunchResponseFilter(); filter.delegation = f.delegation;
        java.util.function.Supplier<Object> write = () -> {
            assertSame(service, filter.create("service", request, next));
            Map<String, Object> saved = store.loadForUpdate((String) f.plan.get("id"));
            assertEquals(900L, ((Number) AiCatalogLaunchInput.map(saved.get("createdGraph")).get("service:app")).longValue());
            assertEquals("completed", AiCatalogLaunchInput.map(AiCatalogLaunchInput.map(saved.get("ledger")).get("create:service:app")).get("state"));
            if (rollback) throw new IllegalStateException("beforeCommit rejected");
            return null;
        };
        if (rollback) assertThrows(IllegalStateException.class, () -> store.transaction(write));
        else store.transaction(write);
        assertEquals(rollback ? 0 : 1, sql.queryForObject("SELECT COUNT(*) FROM fixture_service", Integer.class).intValue());
        Map<String, Object> saved = store.load((String) f.plan.get("id"));
        assertEquals(rollback ? "inflight" : "completed",
                AiCatalogLaunchInput.map(AiCatalogLaunchInput.map(saved.get("ledger")).get("create:service:app")).get("state"));
        // Even a successful HTTP code cannot turn a rolled-back manager call into authoritative success.
        request.setResponseCode(201); request.setResponseObject(service); f.delegation.complete(request, new java.io.IOException("transport closed"));
        saved = store.load((String) f.plan.get("id"));
        assertEquals(rollback ? "unknown" : "completed",
                AiCatalogLaunchInput.map(AiCatalogLaunchInput.map(saved.get("ledger")).get("create:service:app")).get("state"));
        assertEquals(!rollback, saved.get("createdGraph") instanceof Map<?, ?> created && created.containsKey("service:app"));
        verify(next, times(1)).create("service", request);
    }

    static final class Store extends AiCatalogLaunchPlanStore {
        Map<String, Object> plan; int writesAfterIssue;
        @Override public <T> T independent(Supplier<T> operation) { return operation.get(); }
        @Override public Map<String, Object> forStack(long id) { return id == 889L ? plan : null; }
        @Override public Map<String, Object> loadForUpdate(String id) { return Objects.equals(id, plan.get("id")) ? plan : null; }
        @Override public Map<String, Object> load(String id) { return loadForUpdate(id); }
        @Override public void save(Map<String, Object> value) { writesAfterIssue++; }
    }

    final class Fixture {
        final AccountRecord actor = account(42, "user", "person"), project = account(5, "project", "project");
        final AccountRecord launcher = account(100, "service", "machineServiceAccount");
        Account authenticated = launcher; String denial;
        final Policy owner = new AccountPolicy(project, actor, Set.of(), new NoPolicyOptions());
        final Policy servicePolicy = new AccountPolicy(launcher, launcher, Set.of(), new NoPolicyOptions());
        final ObjectManager objects = mock(ObjectManager.class);
        final SchemaFactory schemas = mock(SchemaFactory.class);
        final AiCatalogLaunchGraph graphs = new AiCatalogLaunchGraph();
        final Store store = new Store(); final AiCatalogLaunchDelegation delegation = new AiCatalogLaunchDelegation();
        final TransformInspect handler = new TransformInspect(); final DockerTransformer transformer = mock(DockerTransformer.class);
        final Map<String, Object> inspect = Map.of("Name", "/app", "Config", Map.of("Image", "approved"));
        final Map<String, Object> plan = new LinkedHashMap<>(), values = new LinkedHashMap<>();
        final StackRecord stack = new StackRecord(); final String proof;
        Fixture() throws Exception { this(Map.of()); }
        Fixture(Map<String, String> authorizedReferenceIds) throws Exception {
            var json = new JacksonJsonMapper(); graphs.json = json;
            SchemaImpl script = new SchemaImpl(); script.setId("scripts"); when(schemas.getSchema("scripts")).thenReturn(script);
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("account", "a", "project", "a", "stack", "st", "service", "s"));
            stack.setId(889L); stack.setAccountId(5L); stack.setName("chosen"); stack.setKind("stack"); stack.setState("active");
            when(objects.loadResource(io.cattle.platform.core.model.Stack.class, 889L)).thenReturn(stack);
            when(objects.loadResource(Account.class, 42L)).thenReturn(actor); when(objects.getType(Account.class)).thenReturn("account");
            plan.putAll(Map.of("id", "aicataloglaunch-11111111-1111-1111-1111-111111111111", "actorId", 42L, "accountId", 5L,
                    "internalStackId", 889L, "acceptedAt", 1000L, "status", "accepted", "confirmationState", "approved"));
            plan.putAll(Map.of("credentialId", 7L, "credentialRevision", 3L, "credentialKind", "apiKeyRestricted"));
            plan.put("templateId", "ctm-" + "a".repeat(64)); plan.put("versionId", "cvm-" + "b".repeat(64));
            Map<String, Object> node = Map.of("name", "app", "type", "service", "body", Map.of("name", "app", "stackId", "$createdStack",
                    "launchConfig", Map.of("imageUuid", "docker:approved")), "links", Map.of("serviceLinks", List.of()));
            Map<String, Object> graph = Map.of("services", List.of(node), "volumes", List.of(), "inspect", List.of(Map.of("dockerInspect", inspect)));
            values.put("digest", "a".repeat(64)); values.put("graph", graph); values.put("authorizedReferenceIds", authorizedReferenceIds);
            values.put("graphDigest", graphs.digest(Map.of("nativeGraph", graph, "authorizedReferenceIds", authorizedReferenceIds)));
            values.put("questionSchemaDigest", graphs.digest(List.of()));
            values.put("stackBody", Map.of("name", "chosen"));
            AiCatalogLaunchPrivate privateData = new AiCatalogLaunchPrivate(); privateData.json = json; privateData.secrets = mock(SecretsService.class);
            Map<String, String> encrypted = new LinkedHashMap<>();
            when(privateData.secrets.encrypt(anyLong(), anyString())).thenAnswer(call -> { String ref = "cipher-" + UUID.randomUUID();
                    encrypted.put(ref, call.getArgument(1)); return ref; });
            when(privateData.secrets.decrypt(anyLong(), anyString())).thenAnswer(call -> encrypted.get(call.getArgument(1)));
            plan.put("privateEnvelope", privateData.seal(plan, values)); store.plan = plan;
            AiCatalogLaunchAccess access = new AiCatalogLaunchAccess() {
                @Override public Authorization bound(Map<String, Object> expected, ApiRequest request) {
                    if (denial != null) throw AiCatalogLaunchInput.error(403, denial);
                    return new Authorization(new ApiAuthenticator.CurrentAuthorization(owner, schemas),
                            new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3,
                                    new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE)));
                }
            };
            access.objects = objects; access.idFormatter = formatter;
            ApiKeyAuthorizationService keyAccess = new ApiKeyAuthorizationService(); inject(keyAccess, "auditSinks", List.of(mock(ApiKeyAuditSink.class)));
            ApiAuthenticator authenticator = new ApiAuthenticator() {
                @Override protected Account getAccount(ApiRequest request) { return authenticated; }
                @Override protected Set<io.cattle.platform.api.auth.Identity> getIdentities(Account account, boolean key) {
                    return Set.of(mock(io.cattle.platform.api.auth.Identity.class));
                }
                @Override protected Policy getPolicy(Account account, Account actor, Set<io.cattle.platform.api.auth.Identity> identities,
                        ApiRequest request) { return servicePolicy; }
                @Override protected SchemaFactory getSchemaFactory(Account account, Policy policy, ApiRequest request) { return schemas; }
            };
            AccountDao accounts = mock(AccountDao.class); when(accounts.isActiveAccount(any())).thenReturn(true);
            io.cattle.platform.iaas.api.auth.dao.AuthDao authDao = mock(io.cattle.platform.iaas.api.auth.dao.AuthDao.class);
            when(authDao.getAccountById(88L)).thenReturn(account(88, "project", "foreign"));
            when(authDao.hasAccessToProject(anyLong(), anyLong(), anyBoolean(), anySet())).thenReturn(true);
            inject(authenticator, "authDao", authDao);
            inject(authenticator, "accountDao", accounts); inject(authenticator, "objectManager", objects);
            inject(authenticator, "catalogLaunchDelegation", delegation); inject(authenticator, "apiKeyAuthorization", keyAccess);
            access.authenticator = authenticator;
            delegation.access = access; delegation.store = store; delegation.privateData = privateData; delegation.graphs = graphs;
            delegation.gateway = new AiCatalogLaunchGateway() { @Override public Map<String, Object> material(String p, String t, String v) {
                return Map.of("digest", "a".repeat(64), "questionSchema", List.of());
            } };
            delegation.plans = mock(AiCatalogLaunchPlanService.class);
            ProcessState state = mock(ProcessState.class); when(state.getResource()).thenReturn(stack);
            ProcessInstance process = mock(ProcessInstance.class); when(process.getName()).thenReturn("stack.create");
            EventVO<Object> event = EventVO.newEvent("stack.create;handler=compose-executor"); delegation.decorate(state, process, event);
            proof = (String) AiCatalogLaunchInput.map(AiCatalogLaunchInput.map(event.getData()).get("catalogLaunchDelegation")).get("proof");
            store.writesAfterIssue = 0;
            inject(handler, "transformer", transformer); inject(handler, "jsonMapper", json); inject(handler, "catalogAuthorizers", List.of(delegation));
            ApiContext.newContext(); ApiContext.getContext().setIdFormatter(formatter);
        }
        ApiRequest transform(String token, Map<String, Object> body) throws Exception {
            byte[] bytes = new JacksonJsonMapper().writeValueAsString(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ApiServletContext servlet = mock(ApiServletContext.class); HttpServletRequest http = mock(HttpServletRequest.class);
            when(servlet.getRequest()).thenReturn(http); when(servlet.getResponse()).thenReturn(mock(HttpServletResponse.class));
            when(http.getHeader(AiCatalogLaunchDelegation.HEADER)).thenReturn(token);
            ApiRequest request = new ApiRequest(servlet, schemas) { @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); } };
            request.setType("scripts"); request.setId("transform"); request.setMethod("POST"); request.setVersion("v2-beta");
                    request.setRequestParams(Map.of());
            ApiContext.getContext().setPolicy(null); ApiContext.getContext().setApiRequest(request); return request;
        }
        ApiRequest domain(String type, String method, String id, String action, Map<String, Object> body) {
            ApiRequest request = new ApiRequest(null, schemas); request.setType(type); request.setMethod(method); request.setId(id); request.setAction(action);
            request.setRequestParams(Map.of()); request.setRequestObject(body); return request;
        }
    }

    private static AccountRecord account(long id, String kind, String uuid) {
        AccountRecord row = new AccountRecord(); row.setId(id); row.setKind(kind); row.setUuid(uuid); row.setState("active"); return row;
    }
    private static void inject(Object target, String name, Object value) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try { var field = type.getDeclaredField(name); field.setAccessible(true); field.set(target, value); return; }
            catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
        }
        throw new IllegalStateException(name);
    }
}
