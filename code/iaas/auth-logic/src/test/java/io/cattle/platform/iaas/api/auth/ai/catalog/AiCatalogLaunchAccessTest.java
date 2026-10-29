package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyCodec;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.Test;

public class AiCatalogLaunchAccessTest {
    @Test public void normalOwnerReadsNeedExplicitKeyReadAndEveryFixedGetSchemaWithoutProjectPut() {
        Fixture f = new Fixture();
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.access.launchReads(f.authority(policy(Set.of("create", "update"), false)), false)).getCode());
        f.access.launchReads(f.authority(policy(Set.of("read", "create", "update"), false)), false);
        assertEquals(List.of("GET"), f.projectSchema.getResourceMethods());
        f.service.setCollectionMethods(List.of("POST"));
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.access.launchReads(f.authority(policy(Set.of("read", "create", "update"), false)), false)).getCode());
    }
    @Test public void createOnlyCannotConfigureLinksButCompleteScopedGrantWorksWithoutProjectPutRole() {
        Fixture f = new Fixture();
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.access.childActions(f.authority(policy(Set.of("create", "read"), false)), "service", false)).getCode());
        assertEquals(List.of("GET"), f.projectSchema.getResourceMethods());
        f.access.childActions(f.authority(policy(Set.of("create", "read", "update"), false)), "service", true);
        assertEquals("KeyPolicyDenied", assertThrows(ClientVisibleException.class,
                () -> f.access.childActions(f.authority(policy(Set.of("create", "read", "update"), true)), "service", true)).getCode());
    }

    @Test public void actualServiceActionCapabilityIsRequiredForEveryRebuiltGraph() {
        Fixture f = new Fixture(); var complete = f.authority(policy(Set.of("create", "read", "update"), false));
        f.service.setResourceActions(Map.of("setservicelinks", new Action()));
        f.access.childActions(complete, "service", false);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.access.childActions(complete, "service", true)).getCode());
        f.service.setResourceActions(Map.of("activate", new Action()));
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.access.childActions(complete, "service", false)).getCode());
    }

    @Test public void originalKeyUsesCurrentLockingReadInsteadOfStaleOrdinaryCredential() {
        Fixture f = new Fixture(); CredentialRecord current = credential(); ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
        current.setData(codec.store(current, policy(Set.of("read", "create", "update"), false), 3));
        CredentialRecord stale = credential(); stale.setData(current.getData());
        when(f.access.objects.loadResource(Credential.class, 7L)).thenReturn(stale);
        var rows = DSL.using(SQLDialect.MARIADB);
        f.access.setConfiguration(DSL.using(new MockConnection(context -> {
            assertTrue(context.sql().toLowerCase().contains("for update")); assertArrayEquals(new Object[] { 7L }, context.bindings());
            var result = rows.newResult(CREDENTIAL); result.add(current); return new MockResult[] { new MockResult(1, result) };
        }), SQLDialect.MARIADB).configuration());
        Map<String, Object> plan = new LinkedHashMap<>(Map.of("actorId", 42L, "accountId", 5L,
                "credentialId", 7L, "credentialRevision", 3L, "credentialKind", "apiKeyRestricted"));
        assertEquals(3, f.access.bound(plan, new ApiRequest(null, f.schemas)).key().revision());
        current.setData(codec.store(current, policy(Set.of("read", "create", "update"), false), 4));
        assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class, () -> f.access.bound(plan, new ApiRequest(null, f.schemas))).getCode());
        current.setData(codec.store(current, new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of()), 3));
        assertEquals("KeyPolicyDenied", assertThrows(ClientVisibleException.class, () -> f.access.bound(plan, new ApiRequest(null, f.schemas))).getCode());
        current.setState("inactive");
        assertEquals("ApiKeyRevoked", assertThrows(ClientVisibleException.class, () -> f.access.bound(plan, new ApiRequest(null, f.schemas))).getCode());
        verify(f.access.objects, never()).loadResource(Credential.class, 7L);
    }

    private static CredentialRecord credential() {
        CredentialRecord key = new CredentialRecord(); key.setId(7L); key.setAccountId(42L); key.setKind("apiKeyRestricted");
                key.setState("active"); return key;
    }
    private static ApiKeyPolicy policy(Set<String> operations, boolean denyUpdate) {
        var rules = new ArrayList<ApiKeyPolicy.Rule>();
        rules.add(new ApiKeyPolicy.Rule("project", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5"), operations));
        if (denyUpdate) rules.add(new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.project("1a5"), Set.of("update")));
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, rules);
    }
    private static class Fixture {
        final AiCatalogLaunchAccess access = new AiCatalogLaunchAccess(); final Policy owner = mock(Policy.class);
        final SchemaFactory schemas = mock(SchemaFactory.class); final SchemaImpl service = new SchemaImpl(), projectSchema = new SchemaImpl();
        Fixture() {
            when(owner.getAccountId()).thenReturn(5L); when(owner.getAuthenticatedAsAccountId()).thenReturn(42L);
            service.setId("service"); service.setResourceActions(Map.of("setservicelinks", new Action(), "activate", new Action()));
            service.setResourceMethods(List.of("GET")); service.setCollectionMethods(List.of("GET", "POST"));
            projectSchema.setId("project"); projectSchema.setResourceMethods(List.of("GET"));
            when(schemas.getSchema("service")).thenReturn(service); when(schemas.getSchema("project")).thenReturn(projectSchema);
            SchemaImpl stack = new SchemaImpl(); stack.setId("stack"); stack.setResourceMethods(List.of("GET"));
            when(schemas.getSchema("stack")).thenReturn(stack);
            for (String type : List.of("schema", "serviceConsumeMap")) {
                SchemaImpl read = new SchemaImpl(); read.setId(type); read.setCollectionMethods(List.of("GET")); when(schemas.getSchema(type)).thenReturn(read);
            }
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
                    formatter.setTypeMappings(Map.of("project", "a", "account", "a"));
            access.idFormatter = formatter; access.objects = mock(ObjectManager.class); access.authenticator = mock(ApiAuthenticator.class);
            AccountRecord project = new AccountRecord(); project.setId(5L); project.setKind("project"); project.setState("active");
            when(access.objects.loadResource(Account.class, 5L)).thenReturn(project);
            when(access.authenticator.currentAuthorization(eq(42L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, schemas));
        }
        AiCatalogLaunchAccess.Authorization authority(ApiKeyPolicy policy) {
            return new AiCatalogLaunchAccess.Authorization(new ApiAuthenticator.CurrentAuthorization(owner, schemas),
                    new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, policy));
        }
    }
}
