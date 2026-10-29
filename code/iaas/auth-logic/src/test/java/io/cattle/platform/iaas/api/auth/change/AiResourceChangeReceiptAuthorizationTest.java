package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.*;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import io.github.ibuildthecloud.gdapi.url.NullUrlBuilder;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Real formatter, validator, Key policy evaluator and typed-manager read, including its nested domain GET. */
public class AiResourceChangeReceiptAuthorizationTest {
    private Object enabled;
    @Before public void enable() {
        enabled = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
    }
    @After public void cleanup() {
        ApiContext.remove();
        if (enabled == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", enabled);
    }

    @Test public void scopedKeyReadsItsAcceptedCreatedStackThroughTheActualTypedManagerAndNestedDomainGuard() throws Exception {
        Fixture f = new Fixture();
        String id = "aichange-11111111-1111-1111-1111-111111111111";
        Map<String, Object> persisted = new LinkedHashMap<>(Map.of("id", id, "resourceType", "stack", "operation", "create",
                "internalResourceId", 889L, "resourceId", "1st889", "accountId", 5L, "actorId", 42L));
        persisted.putAll(Map.of("credentialId", 7L, "credentialRevision", 3L, "credentialKind", "apiKeyRestricted",
                "status", "accepted", "confirmationState", "approved", "dispatchAcknowledged", true));
        persisted.put("snapshot", Map.of()); persisted.put("parameters", Map.of("name", "probe"));
        AiResourceChangePlanService service = new AiResourceChangePlanService(); service.domain = f.domain;
        service.auditSinks = List.of(mock(ApiKeyAuditSink.class)); service.locks = mock(LockManager.class);
        when(service.locks.lock(any(), any())).thenAnswer(call -> ((LockCallback<?>) call.getArgument(1)).doWithLock());
        service.store = new AiResourceChangePlanStore() {
            @Override public Map<String, Object> load(String selected) { return id.equals(selected) ? persisted : null; }
            @Override public void save(Map<String, Object> value) { }
        };
        AiResourceChangePlanResourceManager manager = new AiResourceChangePlanResourceManager(); manager.service = service;
        f.request.setId(id); f.guard.authorize(f.request, f.policy);
        Resource response = (Resource) manager.getById("aiResourceChangePlan", id, new ListOptions(f.request));
        assertEquals("succeeded", response.getFields().get("status")); assertEquals(true, response.getFields().get("verifiedResult"));
        assertEquals("1st889", response.getFields().get("resourceId"));
        verify(f.manager, atLeastOnce()).getById(eq("stack"), eq("889"), any());
        assertSame(f.request, ApiContext.getContext().getApiRequest()); assertSame(f.policy, ApiContext.getContext().getPolicy());
    }

    @Test public void validatedStackServiceAndContainerIdsWorkWhilePublicScopeIdsStayStrict() throws Exception {
        Fixture f = new Fixture();
        for (var entry : Map.of("stack", "1st889", "service", "1s890", "container", "1i891").entrySet()) {
            assertNotNull(f.domain.visible(f.request, AiResourceChangeDescriptor.require(entry.getKey(), "create"), entry.getValue()));
            assertEquals("KeyScopeDenied", rejected(() -> f.resolver.parseScopeId(entry.getKey(), entry.getValue().replaceFirst("^1[a-z]+", ""))));
        }
        assertEquals("KeyScopeDenied", rejected(() -> f.resolver.parseScopeId("stack", "1s889")));
        ApiRequest wrongType = new ApiRequest(f.request); wrongType.setRequestServletContext(f.request.getServletContext());
        wrongType.setType("stack"); wrongType.setId("1s889");
        ApiKeyCredentialContext.attach(wrongType, f.key);
        assertEquals("KeyScopeDenied", rejected(() -> f.guard.authorize(wrongType, f.policy)));
    }

    @Test public void decodedIdsStillEnforceExactRulesForeignOwnersChildDenialsAndPinnedRevision() throws Exception {
        Fixture f = new Fixture(); var stack = AiResourceChangeDescriptor.require("stack", "create");
        f.attach(ApiKeyPolicy.Scope.project("1a99"), null);
        assertEquals("KeyScopeDenied", rejected(() -> f.domain.visible(f.request, stack, "1st889")));
        f.attach(ApiKeyPolicy.Scope.resource("stack", "1st999"), null);
        assertEquals("KeyScopeDenied", rejected(() -> f.domain.visible(f.request, stack, "1st889")));
        f.attach(ApiKeyPolicy.Scope.project("1a5"), ApiKeyPolicy.Scope.resource("service", "1s890"));
        assertEquals("KeyPolicyDenied", rejected(() -> f.domain.visible(f.request, AiResourceChangeDescriptor.require("service", "create"), "1s890")));
        f.stack.setAccountId(99L);
        assertEquals("OwnerPermissionDenied", rejected(() -> f.domain.visible(f.request, stack, "1st889")));
        f.stack.setAccountId(5L); when(f.http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("2");
        assertEquals("ApiKeyPolicyChanged", rejected(() -> f.domain.visible(f.request, stack, "1st889")));
        verifyNoInteractions(f.manager);
    }

    private static String rejected(Runnable action) { return assertThrows(ClientVisibleException.class, action::run).getCode(); }
    private static void inject(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static class Fixture {
        final ObjectManager objects = mock(ObjectManager.class);
        final SchemaFactory schemas = mock(SchemaFactory.class);
        final HttpServletRequest http = mock(HttpServletRequest.class);
        final ApiRequest request = new ApiRequest(new ApiServletContext(http, null, null), schemas);
        final ResourceManager manager = mock(ResourceManager.class);
        final AiResourceChangeDomain domain = new AiResourceChangeDomain();
        final ApiKeyTargetResolver resolver = new ApiKeyTargetResolver();
        final ApiKeyAuthorizationService guard = new ApiKeyAuthorizationService();
        final StackRecord stack = new StackRecord();
        final Policy policy;
        ApiKeyCredentialContext key;
        Fixture() throws Exception {
            AccountRecord actor = new AccountRecord(); actor.setId(42L); actor.setState("active");
            AccountRecord project = new AccountRecord(); project.setId(5L); project.setState("active"); project.setKind("project");
            policy = new AccountPolicy(project, actor, Set.of(), new NoPolicyOptions());
            when(objects.loadResource(Account.class, 42L)).thenReturn(actor); when(objects.loadResource(Account.class, 5L)).thenReturn(project);
            stack.setId(889L); stack.setAccountId(5L); stack.setKind("stack"); stack.setState("active"); stack.setName("probe");
            ServiceRecord service = new ServiceRecord(); service.setId(890L); service.setAccountId(5L); service.setStackId(889L); service.setKind("service");
            InstanceRecord container = new InstanceRecord(); container.setId(891L); container.setAccountId(5L); container.setStackId(889L);
            container.setKind("container");
            Map<String, Object> rows = Map.of("stack", stack, "service", service, "container", container);
            when(objects.loadResource(anyString(), any(Long.class))).thenAnswer(call -> rows.get(call.getArgument(0)));
            when(objects.loadResource(Stack.class, 889L)).thenReturn(stack);
            when(manager.getById(anyString(), anyString(), any())).thenAnswer(call -> rows.get(call.getArgument(0)));
            for (String type : List.of("stack", "service", "container", "aiResourceChangePlan")) {
                SchemaImpl schema = new SchemaImpl(); schema.setId(type); schema.setCollectionMethods(List.of("POST"));
                schema.setResourceMethods(List.of("GET")); when(schemas.getSchema(type)).thenReturn(schema);
            }
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("project", "a", "account", "a", "stack", "st", "service", "s", "container", "i"));
            inject(resolver, "objectManager", objects); inject(resolver, "idFormatter", formatter);
            inject(guard, "targets", resolver); inject(guard, "objectManager", objects); inject(guard, "auditSinks", List.of(mock(ApiKeyAuditSink.class)));
            domain.objects = objects; domain.json = new JacksonJsonMapper(); domain.keyAccess = guard;
            domain.keys = mock(AiKeyAuthorization.class); when(domain.keys.actor(any())).thenReturn(policy);
            domain.authenticator = mock(ApiAuthenticator.class);
            when(domain.authenticator.currentAuthorization(eq(42L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(policy, schemas));
            domain.managers = mock(ResourceManagerLocator.class); when(domain.managers.getResourceManagerByType(anyString())).thenReturn(manager);
            domain.validation = new ValidationHandler(); domain.validation.setSupportedMethods(Set.of("GET", "POST", "PUT", "DELETE"));
            request.setType("aiResourceChangePlan"); request.setMethod("GET"); request.setSchemaVersion("v2-beta");
            when(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
            ApiContext.newContext().setApiRequest(request); ApiContext.getContext().setPolicy(policy);
            ApiContext.getContext().setIdFormatter(formatter); request.setUrlBuilder(new NullUrlBuilder());
            attach(ApiKeyPolicy.Scope.project("1a5"), null);
        }
        void attach(ApiKeyPolicy.Scope allow, ApiKeyPolicy.Scope deny) {
            var rules = new java.util.ArrayList<ApiKeyPolicy.Rule>();
            rules.add(new ApiKeyPolicy.Rule("allowed", ApiKeyPolicy.Effect.ALLOW, allow, Set.of("read", "create", "update", "delete")));
            if (deny != null) rules.add(new ApiKeyPolicy.Rule("denied", ApiKeyPolicy.Effect.DENY, deny, Set.of("read")));
            var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, rules, ApiKeyPolicy.Purpose.AI_BRIDGE);
            key = new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, policy); ApiKeyCredentialContext.attach(request, key);
        }
    }
}
