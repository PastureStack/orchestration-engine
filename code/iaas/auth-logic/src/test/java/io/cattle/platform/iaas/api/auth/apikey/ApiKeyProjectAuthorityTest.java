package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

/** Real AccountPolicy catches the gap hidden by passthrough Policy mocks. */
public class ApiKeyProjectAuthorityTest {
    AuthDao dao; AccountDao accounts; Policy policy; AccountRecord principal, project;
    ApiKeyTargetResolver resolver; ApiRequest request; Schema schema; SchemaFactory factory;
    @Before public void setup() {
        var context = ApiContext.newContext();
        dao = mock(AuthDao.class); accounts = mock(AccountDao.class);
        principal = new AccountRecord(); principal.setId(1L); principal.setKind("user");
        project = new AccountRecord(); project.setId(5L); project.setKind("project");
        policy = new AccountPolicy(principal, principal, Set.of(), new NoPolicyOptions());
        resolver = new ApiKeyTargetResolver(); resolver.objectManager = mock(ObjectManager.class);
        resolver.projectAccess = new ProjectAccessService(dao, accounts);
        resolver.idFormatter = mock(IdFormatter.class);
        when(resolver.idFormatter.parseId(anyString())).thenAnswer(i -> i.<String>getArgument(0).replaceFirst("^1a", ""));
        when(resolver.idFormatter.formatId(anyString(), any())).thenAnswer(i -> "1a" + i.getArgument(1));
        when(resolver.objectManager.loadResource("project", "5")).thenReturn(project);
        when(resolver.objectManager.loadResource("project", 5L)).thenReturn(project);
        when(resolver.objectManager.loadResource("account", "5")).thenReturn(project);
        when(resolver.objectManager.loadResource(Account.class, 5L)).thenReturn(project);
        when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(true);
        when(accounts.isActiveAccount(project)).thenReturn(true);
        factory = mock(SchemaFactory.class); schema = mock(Schema.class);
        when(factory.getSchema(anyString())).thenReturn(schema);
        when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT", "DELETE"));
        when(schema.getCollectionMethods()).thenReturn(List.of("GET", "POST"));
        when(schema.getResourceFields()).thenReturn(Map.of());
        request = new ApiRequest(null, factory); request.setType("project"); request.setId("1a5"); request.setMethod("GET");
        context.setApiRequest(request); context.setPolicy(policy);
    }
    @After public void cleanup() { ApiContext.remove(); }
    private ApiKeyPolicy key(boolean grant) {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, grant
                ? List.of(new ApiKeyPolicy.Rule("project-read", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5"), Set.of("read"))) : List.of());
    }
    private ApiKeyAuthorizationService guard(ApiKeyPolicy key) {
        var guard = new ApiKeyAuthorizationService(); guard.targets = resolver;
        guard.auditSinks = List.of(mock(ApiKeyAuditSink.class));
        guard.clock = Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC);
        ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 1, "apiKeyRestricted", 1, key));
        return guard;
    }
    @Test public void explicitProjectGrantAndLiveMembershipBothAllow() {
        guard(key(true)).authorize(request, policy);
        assertEquals("KeyRuleAllowed", request.getAttribute("apiKey.audit.reason"));
        assertEquals("ALLOW", request.getAttribute("apiKey.audit.decision"));
        assertNull(policy.authorizeObject(project));
        assertNull(ApiContext.getContext().getCapabilities(project));
    }
    @Test public void membershipCannotReplaceMissingKeyGrant() {
        var error = assertThrows(ClientVisibleException.class, () -> guard(key(false)).authorize(request, policy));
        assertEquals("KeyScopeDenied", error.getCode());
    }
    @Test public void keyGrantCannotReplaceMissingMembership() {
        when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(false);
        var error = assertThrows(ClientVisibleException.class, () -> guard(key(true)).authorize(request, policy));
        assertEquals("OwnerPermissionDenied", error.getCode());
        assertNull(policy.authorizeObject(project));
    }
    @Test public void revokedMembershipCannotReuseEarlierRequestGrant() {
        resolver.resolveObject("project", project, policy);
        when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(false);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.resolveObject("project", project, policy)).getCode());
    }
    @Test public void targetResolutionDoesNotCreateARequestLocalGrant() {
        resolver.resolveObject("project", project, policy);
        assertNull(policy.authorizeObject(project));
        ApiContext.getContext().setApiRequest(new ApiRequest(null, factory));
        assertNull(policy.authorizeObject(project));
    }
    @Test public void projectResolutionPreservesFormatterCapabilitiesForEveryRole() {
        for (String role : List.of("member", "setMembers", "owner")) {
            when(dao.isProjectOwner(5L, 1L, false, policy.getIdentities())).thenReturn(role.equals("owner"));
            when(dao.canSetProjectMembers(5L, 1L, false, policy.getIdentities())).thenReturn(role.equals("setMembers"));
            for (List<String> before : java.util.Arrays.asList(null, List.of("existing-formatter-capability"))) {
                ApiContext.getContext().setCapabilities(project, before);
                assertEquals("1a5", resolver.resolveObject("project", project, policy).projectId());
                assertEquals(role, before, ApiContext.getContext().getCapabilities(project));
                assertNull(role, policy.authorizeObject(project));
            }
        }
    }
    @Test public void collectionFilteringDoesNotBorrowAnotherProjectsOwnerCapabilities() {
        var other = new AccountRecord(); other.setId(6L); other.setKind("project");
        when(dao.hasAccessToProject(6L, 1L, false, policy.getIdentities())).thenReturn(true);
        when(dao.isProjectOwner(6L, 1L, false, policy.getIdentities())).thenReturn(true);
        when(accounts.isActiveAccount(other)).thenReturn(true);
        when(resolver.objectManager.getType(project)).thenReturn("account");
        when(resolver.objectManager.getType(other)).thenReturn("account");
        var context = ApiContext.getContext();
        context.setCapabilities(project, new java.util.ArrayList<>(List.of("existing-project-capability")));
        context.setCapabilities(other, new java.util.ArrayList<>(List.of("existing-other-capability")));
        var collectionGuard = guard(key(true)); collectionGuard.objectManager = resolver.objectManager;
        assertEquals(List.of(project), collectionGuard.filterCollection(request, List.of(project, other)));
        assertEquals(List.of("existing-project-capability"), context.getCapabilities(project));
        assertEquals(List.of("existing-other-capability"), context.getCapabilities(other));
        assertNull(policy.authorizeObject(project));
        assertNull(policy.authorizeObject(other));
    }
    @Test public void exactProjectReadIsIdenticalForDirectAndAccountBackedCollection() {
        AccountRecord other = otherProject();
        var exact = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("only-project", ApiKeyPolicy.Effect.ALLOW,
                        ApiKeyPolicy.Scope.resource("project", "1a5"), Set.of("read"))));
        var service = collectionGuard(exact, other);
        service.authorize(request, policy);
        assertEquals("ALLOW", request.getAttribute("apiKey.audit.decision"));
        request.setId(null);
        service.authorize(request, policy);
        assertEquals(List.of(project), service.filterCollection(request, List.of(project, other)));
        assertEquals(resolver.resolveObject("project", project, policy), resolver.resolveObject("account", project, policy));
        assertNull(policy.authorizeObject(project));
    }
    @Test public void exactProjectDenyAlsoAppliesToAccountBackedCollection() {
        AccountRecord other = otherProject();
        var exact = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null,
                List.of(new ApiKeyPolicy.Rule("deny-project", ApiKeyPolicy.Effect.DENY,
                        ApiKeyPolicy.Scope.resource("project", "1a5"), Set.of("read"))));
        var service = collectionGuard(exact, other);
        assertEquals("KeyPolicyDenied", assertThrows(ClientVisibleException.class,
                () -> service.authorize(request, policy)).getCode());
        request.setId(null);
        assertEquals(List.of(other), service.filterCollection(request, List.of(project, other)));
    }
    @Test public void exactProjectReadDoesNotBecomeAnEnvironmentWideGrant() {
        policy = new AccountPolicy(project, principal, Set.of(), new NoPolicyOptions());
        ApiContext.getContext().setPolicy(policy);
        when(dao.hasAccessToProject(5L, 5L, false, policy.getIdentities())).thenReturn(true);
        ServiceRecord child = new ServiceRecord(); child.setId(7L); child.setKind("service"); child.setAccountId(5L);
        var exact = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                List.of(new ApiKeyPolicy.Rule("project-metadata", ApiKeyPolicy.Effect.ALLOW,
                        ApiKeyPolicy.Scope.resource("project", "1a5"), Set.of("read"))));
        var service = collectionGuard(exact, project);
        when(resolver.objectManager.getType(child)).thenReturn("service");
        assertEquals(List.of(project), service.filterCollection(request, List.of(project, child)));
        assertEquals("service", resolver.resolveObject("service", child, policy).resourceType());
    }
    private AccountRecord otherProject() {
        AccountRecord other = new AccountRecord(); other.setId(6L); other.setKind("project");
        when(dao.hasAccessToProject(6L, 1L, false, policy.getIdentities())).thenReturn(true);
        when(accounts.isActiveAccount(other)).thenReturn(true);
        return other;
    }
    private ApiKeyAuthorizationService collectionGuard(ApiKeyPolicy key, Account other) {
        var service = guard(key); service.objectManager = resolver.objectManager;
        service.resourceManagers = mock(io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator.class);
        when(resolver.objectManager.getType(project)).thenReturn("account");
        when(resolver.objectManager.getType(other)).thenReturn("account");
        return service;
    }
    @Test public void fullStillUsesOriginalAuthorizationPipeline() {
        guard(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of())).authorize(request, policy);
        assertEquals("KeyFullAccess", request.getAttribute("apiKey.audit.reason"));
        verifyNoInteractions(dao, accounts, resolver.objectManager);
        assertNull(policy.authorizeObject(project));
    }
    @Test public void fullAndLegacyCollectionKeepTheOriginalObjectsAndCapabilities() {
        ApiKeyPolicy full = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of());
        var context = ApiContext.getContext();
        var capabilities = new java.util.ArrayList<>(List.of("original-capability"));
        context.setCapabilities(project, capabilities);
        List<?> values = List.of(project);
        for (ApiKeyPolicy value : java.util.Arrays.asList(null, full)) {
            var service = guard(value); service.objectManager = resolver.objectManager;
            assertSame(values, service.filterCollection(request, values));
            assertSame(capabilities, context.getCapabilities(project));
            assertEquals(List.of("original-capability"), capabilities);
            assertNull(policy.authorizeObject(project));
        }
        verifyNoInteractions(dao, accounts, resolver.objectManager);
    }
    private void acceptedProjectReference(String type, String method) {
        var field = mock(Field.class); when(field.isCreate()).thenReturn(true); when(field.isUpdate()).thenReturn(true);
        when(field.getType()).thenReturn("reference[" + type + "]");
        when(schema.getResourceFields()).thenReturn(Map.of("accountId", field));
        request.setMethod(method); request.setId(null); request.setRequestObject(Map.of("accountId", "1a5"));
    }
    @Test public void acceptedCreateParentsAndReferencesShareLiveProjectAuthority() {
        for (String type : List.of("project", "account")) {
            acceptedProjectReference(type, "POST");
            var parents = resolver.resolve(request, policy);
            assertEquals(1, parents.size()); assertEquals("1a5", parents.getFirst().projectId());
            assertEquals("1a5", resolver.references(request, policy).getFirst().projectId());
        }
        when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(false);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.references(request, policy)).getCode());
    }
    @Test public void acceptedUpdateReferenceAlsoRequiresLiveMembership() {
        acceptedProjectReference("project", "PUT");
        assertEquals("1a5", resolver.references(request, policy).getFirst().projectId());
        when(dao.hasAccessToProject(5L, 1L, false, policy.getIdentities())).thenReturn(false);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.references(request, policy)).getCode());
    }
    @Test public void backgroundProjectResolutionDoesNotSynthesizeHttpContext() {
        ApiContext.remove();
        assertEquals("1a5", resolver.resolveObject("project", project, policy).projectId());
        assertNull(ApiContext.getContext());
        assertNull(policy.authorizeObject(project));
    }
    @Test public void implicitCurrentProjectCreateUsesFreshAuthority() {
        policy = new AccountPolicy(project, principal, Set.of(), new NoPolicyOptions());
        when(dao.hasAccessToProject(5L, 5L, false, policy.getIdentities())).thenReturn(true);
        request.setMethod("POST"); request.setId(null); request.setRequestObject(Map.of());
        assertEquals("1a5", resolver.resolve(request, policy).getFirst().projectId());
        when(dao.hasAccessToProject(5L, 5L, false, policy.getIdentities())).thenReturn(false);
        assertTrue(resolver.resolve(request, policy).isEmpty());
    }
    @Test public void nonProjectAccountCannotBorrowProjectMembership() {
        project.setKind("user");
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.resolveObject("account", project, policy)).getCode());
        verifyNoInteractions(dao, accounts);
    }
}
