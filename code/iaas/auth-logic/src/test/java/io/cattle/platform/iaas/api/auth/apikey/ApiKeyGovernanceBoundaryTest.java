package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** A scoped business grant must not mint authority outside that grant. */
public class ApiKeyGovernanceBoundaryTest {
    private ApiKeyAuthorizationService service;
    private ApiKeyTargetResolver targets;
    private ApiKeyAuditSink audit;
    private Policy owner;
    private ApiRequest request;
    private AccountRecord account;
    private final Instant now = Instant.parse("2026-10-11T00:00:00Z");

    @Before public void setup() {
        ApiContext.newContext();
        owner = mock(Policy.class);
        ApiContext.getContext().setPolicy(owner);
        SchemaFactory factory = mock(SchemaFactory.class);
        Schema schema = mock(Schema.class);
        when(factory.getSchema(anyString())).thenReturn(schema);
        when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT", "DELETE"));
        when(schema.getCollectionMethods()).thenReturn(List.of("GET", "POST"));
        request = new ApiRequest(null, factory);
        request.setType("account");
        request.setId("1a21");
        request.setMethod("PUT");
        ApiContext.getContext().setApiRequest(request);
        service = new ApiKeyAuthorizationService();
        targets = mock(ApiKeyTargetResolver.class);
        service.targets = targets;
        when(targets.resolve(request, owner)).thenAnswer(invocation -> List.of(
                ApiKeyPolicyEvaluator.Target.platformResource(request.getType(),
                        request.getId() == null ? "new" : request.getId())));
        when(targets.references(request, owner)).thenReturn(List.of());
        audit = mock(ApiKeyAuditSink.class);
        service.auditSinks = List.of(audit);
        service.objectManager = mock(ObjectManager.class);
        DefaultIdFormatter formatter = new DefaultIdFormatter();
        formatter.setSchemaFactory(factory);
        ApiContext.getContext().setIdFormatter(formatter);
        account = new AccountRecord();
        account.setId(21L);
        account.setKind("user");
        account.setExternalId("existing-user");
        account.setExternalIdType("oidc_user");
        when(service.objectManager.loadResource(Account.class, "21")).thenReturn(account);
        service.resourceManagers = mock(ResourceManagerLocator.class);
        service.clock = Clock.fixed(now, ZoneOffset.UTC);
        use(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null, List.of()));
    }

    @After public void cleanup() { ApiContext.remove(); }

    private void use(ApiKeyPolicy policy) {
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(1, 5, "apiKey", 1, policy));
    }

    private void denied() {
        ClientVisibleException error = assertThrows(ClientVisibleException.class,
                () -> service.authorize(request, owner));
        assertEquals("ApiKeyRestrictedDelegationUnsupported", error.getCode());
        assertEquals(403, error.getStatus());
        assertEquals("DENY", request.getAttribute("apiKey.audit.decision"));
        verify(audit).recordDecision(request, owner);
        verify(targets, never()).resolve(request, owner);
    }

    @Test public void customCannotPromoteAnotherAccount() {
        request.setRequestObject(Map.of("kind", "admin"));
        denied();
    }

    @Test public void customCannotRewriteExternalIdentity() {
        request.setRequestObject(Map.of("externalId", "other-user", "externalIdType", "oidc_user"));
        denied();
    }

    @Test public void caseOrNullFieldDoesNotBypassSecurityWriteGuard() {
        Map<String, Object> body = new HashMap<>();
        body.put("ExTeRnAlId", null);
        request.setRequestObject(body);
        denied();
    }

    @Test public void metadataAndRoleInSameWriteAreRejectedTogether() {
        request.setRequestObject(Map.of("name", "ordinary metadata", "kind", "admin"));
        denied();
    }

    @Test public void accountMetadataStillUsesTheNormalScopeCheck() {
        request.setRequestObject(Map.of("name", "renamed", "description", "description"));
        service.authorize(request, owner);
        assertEquals("ALLOW", request.getAttribute("apiKey.audit.decision"));
        verify(targets).resolve(request, owner);
    }

    @Test public void unchangedSecurityFieldsInFullAccountDtoRemainEditable() {
        request.setRequestObject(Map.of("name", "renamed", "kind", "user",
                "externalId", "existing-user", "externalIdType", "oidc_user"));
        service.authorize(request, owner);
        verify(targets).resolve(request, owner);
    }

    @Test public void numericAccountIdHasTheSameFieldComparison() {
        request.setId("21");
        request.setRequestObject(Map.of("name", "renamed", "kind", "user"));
        service.authorize(request, owner);
        verify(targets).resolve(request, owner);
        request.setRequestObject(Map.of("kind", "admin"));
        assertThrows(ClientVisibleException.class, () -> service.authorize(request, owner));
    }

    @Test public void unchangedSecurityFieldsCannotReapplyRevokedAuthority() {
        account.setKind("admin");
        Map<String, Object> original = Map.of("name", "renamed", "KiNd", "admin",
                "externalId", "existing-user", "externalIdType", "oidc_user");
        request.setRequestObject(original);
        service.authorize(request, owner);
        // A later manager load may see a concurrent legitimate downgrade or
        // identity rebind. Only metadata must reach that later persistence step.
        account.setKind("user");
        account.setExternalId("rebound-user");
        assertEquals(Map.of("name", "renamed"), request.getRequestObject());
        assertEquals("admin", original.get("KiNd"));
        assertEquals("user", account.getKind());
        assertEquals("rebound-user", account.getExternalId());
    }

    @Test public void malformedAccountIdFailsClosedWithAnAuditedDenial() {
        request.setId("1a!not-a-number");
        request.setRequestObject(Map.of("kind", "user"));
        denied();
        verify(service.objectManager, never()).loadResource(eq(Account.class), anyString());
    }

    @Test public void overflowingAccountIdFailsClosedWithAnAuditedDenial() {
        request.setId("9999999999999999999999999999999999");
        request.setRequestObject(Map.of("kind", "user"));
        denied();
        verify(service.objectManager, never()).loadResource(eq(Account.class), anyString());
    }

    @Test public void projectMetadataStillUsesTheNormalScopeCheck() {
        request.setType("project");
        request.setRequestObject(Map.of("name", "renamed", "description", "description"));
        service.authorize(request, owner);
        verify(targets).resolve(request, owner);
    }

    @Test public void customCannotSupplyMembershipsOnProjectCreation() {
        request.setType("project");
        request.setId(null);
        request.setMethod("POST");
        request.setRequestObject(Map.of("members", List.of(Map.of("role", "owner"))));
        denied();
    }

    @Test public void projectGrantCannotEnrollPersonalMfa() {
        request.setType("mfaOperation");
        request.setId(null);
        request.setMethod("POST");
        request.setRequestObject(Map.of("operation", "beginTotpEnrollment"));
        when(targets.resolve(request, owner)).thenReturn(List.of(
                ApiKeyPolicyEvaluator.Target.projectResource("mfaOperation", "new", "1a5")));
        denied();
    }

    @Test public void customCannotCreatePersonalPassword() {
        request.setType("password");
        request.setId(null);
        request.setMethod("POST");
        request.setRequestObject(Map.of("accountId", "1a21", "secretValue", "test-only-not-a-credential"));
        denied();
    }

    @Test public void customCannotTransferIdentityAuthority() {
        request.setType("authIdentityOperation");
        request.setId(null);
        request.setMethod("POST");
        request.setRequestObject(Map.of("operation", "reassign", "targetAccountId", "1a21"));
        denied();
    }

    @Test public void customCannotWriteProviderConfiguration() {
        request.setType("localAuthConfig");
        request.setRequestObject(Map.of("enabled", false));
        denied();
    }

    @Test public void numericSecuritySettingUsesResolvedName() {
        request.setType("setting");
        request.setId("42");
        request.setRequestObject(Map.of("name", "innocent", "value", "false"));
        when(targets.settingName(request)).thenReturn("api.security.enabled");
        denied();
    }

    @Test public void activeSettingAliasUsesTheSameSecurityBoundary() {
        request.setType("activeSetting");
        request.setId("1s42");
        when(targets.settingName(request)).thenReturn("api.auth.provider.config");
        denied();
    }

    @Test public void securitySettingCreateCannotBypassUsingCollection() {
        request.setType("setting");
        request.setId(null);
        request.setMethod("POST");
        when(targets.settingName(request)).thenReturn("system.stack.auth.url");
        denied();
    }

    @Test public void ordinarySettingStillUsesTheNormalScopeCheck() {
        request.setType("setting");
        when(targets.settingName(request)).thenReturn("catalog.url");
        service.authorize(request, owner);
        verify(targets).resolve(request, owner);
    }

    @Test public void securityReadsRemainGovernedByNormalPolicy() {
        request.setType("mfaSettings");
        request.setMethod("GET");
        service.authorize(request, owner);
        verify(targets).resolve(request, owner);
    }

    @Test public void ordinaryContainerUpdateRemainsAvailable() {
        request.setType("container");
        request.setId("1i1");
        request.setRequestObject(Map.of("name", "renamed", "description", "description"));
        service.authorize(request, owner);
        assertEquals("ALLOW", request.getAttribute("apiKey.audit.decision"));
    }

    @Test public void fullIncludingValidExpiryRetainsExistingSecurityAuthority() {
        request.setRequestObject(Map.of("kind", "admin"));
        use(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW,
                now.plusSeconds(60), List.of()));
        service.authorize(request, owner);
        verifyNoInteractions(targets);
        assertEquals("ALLOW", request.getAttribute("apiKey.audit.decision"));
    }

    @Test public void legacyRetainsExistingSecurityAuthority() {
        request.setRequestObject(Map.of("kind", "admin"));
        use(null);
        service.authorize(request, owner);
        verifyNoInteractions(targets);
    }
}
