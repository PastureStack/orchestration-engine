package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyCodec;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyManagementService;
import io.cattle.platform.iaas.api.credential.ApiKeyOutputFilter;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractBaseResourceManager;
import io.github.ibuildthecloud.gdapi.response.ResourceOutputFilterManager;
import io.github.ibuildthecloud.gdapi.url.NullUrlBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

public class AiApiKeyReadViewTest {
    private static final String SECRET = "PRIVATE_CREDENTIAL_MUST_NOT_LEAVE";
    private static final ApiKeyPolicyCodec CODEC = new ApiKeyPolicyCodec();
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void realDefaultFormatterPreservesWirePreviewAndExactEvaluatorSemanticsWithoutRewritingConfiguredScopes() throws Exception {
        for (String mode : List.of("numeric", "wire", "other-id", "wrong-kind", "hidden", "foreign-context", "wrong-wire-type")) {
            Fixture f = new Fixture("apiKeyRestricted");
            when(f.schemas.getBaseType(anyString())).thenAnswer(call -> switch ((String) call.getArgument(0)) {
            case "apiKey", "apiKeyRestricted" -> "credential";
            case "project" -> "account";
            case "dnsService", "externalService", "loadBalancerService" -> "service";
            default -> call.getArgument(0);
            });
            var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(f.schemas);
            formatter.setTypeMappings(Map.of("credential", "c", "account", "a", "service", "s", "stack", "st"));
            ApiContext.getContext().setIdFormatter(formatter);
            String scopeId = "numeric".equals(mode) ? "9" : "wrong-wire-type".equals(mode) ? "1st9" : "1s9";
            f.policy(List.of(new ApiKeyPolicy.Rule("configured", ApiKeyPolicy.Effect.ALLOW,
                    ApiKeyPolicy.Scope.resource("service", scopeId), Set.of("read"))), 3, ApiKeyPolicy.Mode.CUSTOM);
            Map<String, Object> stored = CODEC.encode(CODEC.read(f.credential));
            if ("other-id".equals(mode)) f.serviceRow.setId(10L);
            if ("wrong-kind".equals(mode)) f.nativeType = "dnsService";
            if ("hidden".equals(mode)) f.fields.get("name").setIncludeInList(false);
            if ("foreign-context".equals(mode)) f.serviceRow.setAccountId(88L);
            // Keep the real native convertResponse -> output filter chain, with the real formatter at the DTO boundary.
            doAnswer(call -> {
                Resource nativeDto = f.nativeManager.convertResponse((Object) call.getArgument(0), (ApiRequest) call.getArgument(1));
                Map<String, Object> fields = new LinkedHashMap<>(nativeDto.getFields());
                fields.put("accountId", formatter.formatId("account", Long.parseLong((String) fields.get("accountId"))));
                return new ResourceImpl(formatter.formatId(nativeDto.getType(), Long.parseLong(nativeDto.getId())), nativeDto.getType(), fields);
            }).when(f.manager).convertResponse(any(Object.class), any(ApiRequest.class));
            Map<String, Object> read = f.detail(); Map<?, ?> scope = (Map<?, ?>) ((Map<?, ?>) rules(read).getFirst()).get("scope");
            var evaluator = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator();
            var target = io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator.Target.projectResource("service", "1s9", "1a77");
            var nativeDecision = evaluator.evaluate(CODEC.read(f.credential),
                    new io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator.Request(true, "read", true, List.of(target)),
                    java.time.Instant.parse("2026-11-06T00:00:00Z"));
            boolean resolved = "wire".equals(mode);
            if (List.of("numeric", "wire", "wrong-wire-type").contains(mode))
                assertEquals(mode, resolved, nativeDecision.allowed());
            // Other negatives use a matching configured wire scope, so numeric mismatch cannot hide a missing guard.
            assertEquals(scopeId, CODEC.read(f.credential).getRules().getFirst().scope().resourceId());
            assertEquals(mode, resolved ? "resolved" : "unavailable", scope.get("availability"));
            assertEquals(resolved, read.get("scopePreviewComplete")); assertEquals(1, f.scopeReads);
            assertEquals(stored, CODEC.encode(CODEC.read(f.credential))); // No rewrite of configured rule IDs/scopes/policy.
            if (resolved) assertEquals("1s9", ((Map<?, ?>) scope.get("target")).get("id"));
            else { assertFalse(scope.containsKey("target")); assertFalse(read.toString().contains("HIDDEN_TARGET_NAME")); }
        }
    }

    @Test public void normalNativeConvertChainReadsLegacyAndRestrictedConfiguredPolicyWithoutSecretsOrListExpansion() throws Exception {
        for (String kind : List.of("apiKey", "apiKeyRestricted")) {
            Fixture f = new Fixture(kind);
            if ("apiKeyRestricted".equals(kind)) f.policy(List.of(), 7, ApiKeyPolicy.Mode.CLOSED);
            Resource nativeDto = f.nativeManager.convertResponse(f.credential, f.domain());
            assertTrue(nativeDto.getFields().get("apiKeyPolicy") instanceof Map<?, ?>);
            var detail = f.detail();
            assertEquals("configured-policy-read-only", detail.get("representation"));
            assertEquals("not-evaluated", detail.get("effectiveAuthorization"));
            assertEquals(false, detail.get("previewComplete"));
            assertEquals("apiKey".equals(kind) ? 0L : 7L, detail.get("revision"));
            Map<?, ?> policy = (Map<?, ?>) detail.get("configuredPolicy");
            assertEquals("apiKey".equals(kind) ? "full" : "closed", policy.get("mode"));
            assertEquals("apiKey".equals(kind) ? "allow" : "deny", policy.get("defaultEffect"));
            assertEquals(List.of(), policy.get("rules")); assertFalse(detail.toString().contains(SECRET));
            assertFalse(detail.containsKey("persistedPolicy"));
            Map<String, Object> list = f.service.read(f.request(false));
            assertFalse(list.toString().contains("apiKeyPolicyRead")); assertEquals(0, f.scopeReads);
        }
    }

    @Test public void all128ConfiguredRulesAndDenialsRemainWhileScopeReadsAreDeduplicatedAndCeilingsStayPartial() throws Exception {
        Fixture f = new Fixture("apiKeyRestricted"); List<ApiKeyPolicy.Rule> rules = new ArrayList<>();
        for (int i=0; i<128; i++) rules.add(new ApiKeyPolicy.Rule("rule-"+String.format("%03d", i),
                i%2==0 ? ApiKeyPolicy.Effect.DENY : ApiKeyPolicy.Effect.ALLOW,
                ApiKeyPolicy.Scope.resource("service", "9"), Set.of("read", "update")));
        f.policy(rules, 3, ApiKeyPolicy.Mode.CUSTOM);
        Map<String, Object> read = f.detail(); List<?> actual = rules(read);
        assertEquals(128, actual.size()); assertEquals(64, actual.stream().filter(row -> "deny".equals(((Map<?, ?>) row).get("effect"))).count());
        assertEquals(1, f.scopeReads); assertEquals(true, read.get("allConfiguredRulesIncluded"));
        assertEquals("deny", ((Map<?, ?>) read.get("configuredPolicy")).get("defaultEffect"));
        Map<?, ?> first = (Map<?, ?>) actual.getFirst();
        assertEquals("resolved", ((Map<?, ?>) first.get("scope")).get("availability"));
        assertEquals("native-owner-ceiling", ((Map<?, ?>) first.get("ownerCapabilities")).get("basis"));
        List<?> operations = (List<?>) ((Map<?, ?>) first.get("ownerCapabilities")).get("operations");
        assertTrue(operations.stream().anyMatch(row -> "read".equals(((Map<?, ?>) row).get("operation"))
                && "observed".equals(((Map<?, ?>) row).get("status"))));
        assertTrue(operations.stream().anyMatch(row -> "create".equals(((Map<?, ?>) row).get("operation"))
                && "unknown".equals(((Map<?, ?>) row).get("status"))));
        assertEquals(false, read.get("previewComplete"));
        verify(f.keyAccess, atLeast(3)).authorize(any(), any(Policy.class));
    }

    @Test public void nativeScopeDenialWrongKindAndForeignReadbackStayIncompleteWithoutTargetIdentifiers() throws Exception {
        for (String mode : List.of("deny", "foreign", "kind", "hidden")) {
            Fixture f = new Fixture("apiKeyRestricted"); f.oneScope();
            if ("deny".equals(mode)) doAnswer(call -> {
                ApiRequest request = call.getArgument(0);
                if ("service".equals(request.getType())) throw new ClientVisibleException(403, "KeyScopeDenied", "private", null);
                return null;
            }).when(f.keyAccess).authorize(any(), any());
            if ("foreign".equals(mode)) f.serviceRow.setAccountId(88L);
            if ("kind".equals(mode)) f.nativeType = "dnsService";
            if ("hidden".equals(mode)) f.fields.get("name").setIncludeInList(false);
            Map<String, Object> read = f.detail(); Map<?, ?> scope = (Map<?, ?>) ((Map<?, ?>) rules(read).getFirst()).get("scope");
            assertEquals("unavailable", scope.get("availability")); assertEquals("SCOPE_TARGET_UNAVAILABLE", scope.get("reason"));
            assertFalse(scope.containsKey("target")); assertFalse(scope.containsKey("resourceId"));
            assertEquals(false, read.get("scopePreviewComplete")); assertEquals("SCOPE_PREVIEW_INCOMPLETE", read.get("reason"));
            assertFalse(read.toString().contains("HIDDEN_TARGET_NAME"));
        }
    }

    @Test public void freshPolicyRevisionFieldAndCredentialRevocationCannotBecomeAConfiguredPolicySnapshot() throws Exception {
        for (String mode : List.of("revision", "field", "revoked")) {
            Fixture f = new Fixture("apiKeyRestricted"); f.oneScope();
            when(f.manager.getById(eq("service"), eq("9"), any())).thenAnswer(call -> {
                if ("revision".equals(mode)) f.policy(List.of(), 4, ApiKeyPolicy.Mode.CLOSED);
                if ("field".equals(mode)) f.fields.get("apiKeyPolicy").setIncludeInList(false);
                if ("revoked".equals(mode)) when(f.service.keyAuthorization.actor(any())).thenThrow(
                        new ClientVisibleException(403, "ApiKeyRevoked", "private", null));
                return f.serviceRow;
            });
            ClientVisibleException failure = assertThrows(ClientVisibleException.class, f::detail);
            assertEquals("revision".equals(mode) ? "ApiKeyPolicyRevisionConflict"
                    : "field".equals(mode) ? "OwnerPermissionDenied" : "ApiKeyRevoked", failure.getCode());
        }
        Fixture wrongKind = new Fixture("apiKey"); wrongKind.policy(List.of(), 1, ApiKeyPolicy.Mode.CLOSED); wrongKind.credential.setKind("apiKeyRestricted");
        assertEquals("ResourceViewUnavailable", assertThrows(ClientVisibleException.class, wrongKind::detail).getCode());
        Fixture hidden = new Fixture("apiKey"); hidden.fields.get("apiKeyPolicy").setIncludeInList(false);
        assertEquals("POLICY_FIELDS_UNAVAILABLE", hidden.detail().get("reason")); assertEquals(0, hidden.scopeReads);
    }

    private static List<?> rules(Map<String, Object> read) { return (List<?>) ((Map<?, ?>) read.get("configuredPolicy")).get("rules"); }

    static final class Fixture extends AiResourceViewServiceTest.Fixture {
        final String kind;
        final CredentialRecord credential = new CredentialRecord();
        final ServiceRecord serviceRow = new ServiceRecord();
        final Map<String, FieldImpl> fields = new LinkedHashMap<>();
        String nativeType = "service";
        int scopeReads;
        final AbstractBaseResourceManager nativeManager;
        Fixture(String kind) throws Exception {
            this.kind=kind; credential.setId(42L); credential.setAccountId(42L); credential.setKind(kind);
            credential.setState("active"); credential.setName("same-name"); credential.setData(Map.of());
            credential.setSecretValue(SECRET); credential.setPublicValue(SECRET);
            serviceRow.setId(9L); serviceRow.setAccountId(77L); serviceRow.setName("HIDDEN_TARGET_NAME"); serviceRow.setKind("service");
            for (String name : List.of("apiKeyPolicy", "apiKeyPolicyRevision", "accountId", "name", "state", "kind")) {
                FieldImpl field = new FieldImpl(); field.setIncludeInList(true); fields.put(name, field);
            }
            when(schema.getResourceFields()).thenReturn((Map) fields);
            when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT")); when(schema.getResourceActions()).thenReturn(Map.of());
            service.objects=mock(ObjectManager.class);
            when(service.objects.loadResource(eq(kind), eq(42L))).thenReturn(credential);
            ServiceRecord candidate = new ServiceRecord(); candidate.setId(9L); candidate.setAccountId(77L); candidate.setKind("service");
            when(service.objects.loadResource(eq("service"), eq(9L))).thenReturn(candidate);
            ApiKeyOutputFilter output = new ApiKeyOutputFilter();
            var policyField=ApiKeyOutputFilter.class.getDeclaredField("policyManager"); policyField.setAccessible(true);
            policyField.set(output, new ApiKeyPolicyManagementService());
            ResourceOutputFilterManager filters=mock(ResourceOutputFilterManager.class); when(filters.getOutputFilter(any())).thenReturn(output);
            nativeManager = new AbstractBaseResourceManager() {
                @Override public String[] getTypes() { return new String[] {"apiKey", "apiKeyRestricted", "service"}; }
                @Override public Class<?>[] getTypeClasses() { return new Class<?>[0]; }
                @Override protected Object listInternal(io.github.ibuildthecloud.gdapi.factory.SchemaFactory factory, String type,
                        Map<Object, Object> criteria, io.github.ibuildthecloud.gdapi.model.ListOptions options) {
                    throw new AssertionError("conversion-only fixture"); }
                @Override protected Object createInternal(String type, ApiRequest request) { throw new AssertionError("no mutation"); }
                @Override protected Object updateInternal(String type, String id, Object row, ApiRequest request) { throw new AssertionError("no mutation"); }
                @Override protected Object deleteInternal(String type, String id, Object row, ApiRequest request) { throw new AssertionError("no mutation"); }
                @Override protected Object getLinkInternal(String type, String id, String link, ApiRequest request) { throw new AssertionError("no link"); }
                @Override protected Object resourceActionInternal(Object row, ApiRequest request) { throw new AssertionError("no action"); }
                @Override protected Object collectionActionInternal(Object rows, ApiRequest request) { throw new AssertionError("no action"); }
                @Override protected Resource createResource(Object row, IdFormatter formatter, ApiRequest request) {
                    request.setUrlBuilder(new NullUrlBuilder());
                    Map<String, Object> value=new LinkedHashMap<>();
                    if(row instanceof CredentialRecord key) {
                        value.put("name", key.getName()); value.put("accountId", key.getAccountId().toString());
                        value.put("state", key.getState()); value.put("kind", key.getKind());
                        value.put("publicValue", key.getPublicValue()); value.put("secretValue", key.getSecretValue());
                        return new ResourceImpl(key.getId().toString(), key.getKind(), value);
                    }
                    ServiceRecord service=(ServiceRecord)row;
                    value.put("name", service.getName()); value.put("accountId", service.getAccountId().toString()); value.put("state", "active");
                    return new ResourceImpl(service.getId().toString(), nativeType, value);
                }
            };
            nativeManager.setOutputFilterManager(filters);
            when(manager.convertResponse(any(Object.class), any(ApiRequest.class))).thenAnswer(
                    call -> nativeManager.convertResponse((Object)call.getArgument(0), (ApiRequest)call.getArgument(1)));
            when(manager.getById(eq(kind), eq("42"), any())).thenReturn(credential);
            when(manager.list(eq(kind), any())).thenReturn(List.of(credential));
            when(manager.getById(eq("service"), eq("9"), any())).thenAnswer(call -> {scopeReads++; return serviceRow; });
            ApiContext.getContext().setIdFormatter(new IdentityFormatter());
        }
        ApiRequest request(boolean detail) {
            var body=new LinkedHashMap<String, Object>(); body.put("resourceType", kind); if(detail)body.put("resourceId", "42");
            ApiRequest request=request(body); request.setType("aiResourceView"); request.setUrlBuilder(new NullUrlBuilder()); return request;
        }
        ApiRequest domain() {ApiRequest request=request(true); request.setType(kind); request.setSchemaFactory(schemas);
            ApiContext.getContext().setApiRequest(request); return request; }
        Map<String, Object> detail() {
            Map<?, ?> item=(Map<?, ?>)((List<?>)service.read(request(true)).get("data")).getFirst();
            assertTrue("normal native DTO must become a detail-only configured policy", item.get("apiKeyPolicyRead") instanceof Map<?, ?>);
            return (Map<String, Object>)item.get("apiKeyPolicyRead");
        }
        void oneScope() {
            policy(List.of(new ApiKeyPolicy.Rule("deny-remains", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource("service", "9"), Set.of("read"))),
                3, ApiKeyPolicy.Mode.CUSTOM); }
        void policy(List<ApiKeyPolicy.Rule> rules, long revision, ApiKeyPolicy.Mode mode) {
            credential.setData(CODEC.store(credential, new ApiKeyPolicy(mode,
                    mode==ApiKeyPolicy.Mode.FULL?ApiKeyPolicy.Effect.ALLOW:ApiKeyPolicy.Effect.DENY, null, rules), revision));
        }
    }
}
