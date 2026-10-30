package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.IpAddressNicMapTable.IP_ADDRESS_NIC_MAP;

import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.*;
import io.cattle.platform.core.model.tables.records.*;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;

public class StandaloneNetworkCleanupTest {
    ApiKeyProcessAuthorization hook;
    ApiKeyTargetResolver targets;
    CredentialRecord key;
    InstanceRecord instance;
    NicRecord nic;
    IpAddressRecord ip;
    IpAddressNicMapRecord map;
    Schema schema;
    AuthDao roles;
    Map<String, Object> binding;
    Instant now = Instant.parse("2026-10-30T03:50:00Z");

    @Before public void setup() {
        ApiContext.remove();
        hook = new ApiKeyProcessAuthorization(); hook.objectManager = mock(ObjectManager.class);
        hook.authenticator = mock(ApiAuthenticator.class); hook.clock = Clock.fixed(now, ZoneOffset.UTC);
        var project = new AccountRecord(); project.setId(5L); project.setKind("project"); project.setState("active");
        var principal = new AccountRecord(); principal.setId(1L);
        var owner = new AccountPolicy(project, principal, Set.of(), new NoPolicyOptions());
        var factory = mock(SchemaFactory.class); schema = mock(Schema.class);
        when(schema.getResourceMethods()).thenReturn(List.of("DELETE")); when(factory.getSchema("container")).thenReturn(schema);
        when(factory.getBaseType("container")).thenReturn("instance"); when(factory.getBaseType("project")).thenReturn("account");
        when(hook.objectManager.getSchemaFactory()).thenReturn(factory);
        when(hook.authenticator.currentAuthorization(eq(1L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, factory));
        targets = new ApiKeyTargetResolver(); targets.objectManager = hook.objectManager;
        roles = mock(AuthDao.class); var accounts = mock(io.cattle.platform.core.dao.AccountDao.class);
        when(roles.hasAccessToProject(eq(5L), eq(5L), eq(false), any())).thenReturn(true);
        when(accounts.isActiveAccount(project)).thenReturn(true); targets.projectAccess = new ProjectAccessService(roles, accounts);
        var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(factory);
        formatter.setTypeMappings(Map.of("credential", "c", "account", "a", "instance", "i", "nic", "n", "service", "s",
                "ipAddress", "ip", "ipAddressNicMap", "ipm"));
        targets.idFormatter = formatter; hook.targets = targets;
        key = new CredentialRecord(); key.setId(1L); key.setAccountId(1L); key.setKind("apiKeyRestricted"); key.setState("active");
        when(hook.objectManager.loadResource(Credential.class, 1L)).thenReturn(key);
        when(hook.objectManager.loadResource(Account.class, 5L)).thenReturn(project);
        instance = new InstanceRecord(); instance.setId(3L); instance.setUuid("instance-original");
        instance.setAccountId(5L); instance.setKind("container"); instance.setState("removing");
        nic = new NicRecord(); nic.setId(15L); nic.setUuid("nic-original"); nic.setAccountId(5L);
        nic.setInstanceId(3L); nic.setNetworkId(4L); nic.setState("removed"); nic.setRemoved(new Date(1));
        ip = new IpAddressRecord(); ip.setId(16L); ip.setUuid("ip-original"); ip.setAccountId(5L); ip.setState("inactive");
        map = new IpAddressNicMapRecord(); map.setId(17L); map.setUuid("map-original");
        map.setNicId(15L); map.setIpAddressId(16L); map.setState("active");
        when(hook.objectManager.loadResource("container", 3L)).thenReturn(instance);
        when(hook.objectManager.loadResource(Instance.class, 3L)).thenReturn(instance);
        when(hook.objectManager.loadResource("nic", "15")).thenReturn(nic);
        when(hook.objectManager.loadResource(Nic.class, 15L)).thenReturn(nic);
        when(hook.objectManager.loadResource("ipAddress", "16")).thenReturn(ip);
        when(hook.objectManager.loadResource(IpAddress.class, 16L)).thenReturn(ip);
        when(hook.objectManager.loadResource(IpAddressNicMap.class, 17L)).thenReturn(map);
        when(hook.objectManager.find(IpAddressNicMap.class, IP_ADDRESS_NIC_MAP.IP_ADDRESS_ID, 16L,
                IP_ADDRESS_NIC_MAP.REMOVED, null)).thenReturn(List.of(map));
        binding = new LinkedHashMap<>(Map.of("keyId", "1c1", "policyRevision", 1L, "principalAccountId", 1L,
                "accountId", 5L, "operation", "delete", "targetType", "container", "targetId", "3",
                "requestMethod", "DELETE", "requestCollection", false));
        binding.put("preview", false); binding.put("requestId", "aichange-native-remove");
        policy(List.of());
    }

    private void policy(List<ApiKeyPolicy.Rule> extra) {
        var rules = new java.util.ArrayList<ApiKeyPolicy.Rule>();
        rules.add(new ApiKeyPolicy.Rule("project", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5"),
                Set.of("read", "create", "update", "delete"))); rules.addAll(extra);
        key.setData(new ApiKeyPolicyCodec().store(key, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY,
                now.plusSeconds(3600), rules, ApiKeyPolicy.Purpose.AI_BRIDGE), 1));
    }

    private ProcessRecord child(String process, String type, String id) {
        ProcessRecord record = new ProcessRecord(new LaunchConfiguration(process, type, id, 5L, 0,
                Map.of(ProcessAuthorization.DATA_KEY, binding)), null, "offline");
        assertNull(record.getId()); return record;
    }

    @Test public void nativeRemoveReentryAcceptsOnlyItsRetiredNicAndExactIpGraph() {
        EngineContext engine = EngineContext.getEngineContext();
        var parent = engine.pushVerifiedExecution("instance", "3", "instance.remove", binding);
        try {
            hook.beforeExecution(child("nic.remove", "nic", "15"), binding);
            // The native children() query retains tombstones; owner remove may
            // re-enter after a NIC already reached removed/purging.
            nic.setState("purging"); hook.beforeExecution(child("nic.remove", "nic", "15"), binding);
            nic.setState("removed");
            var frame = engine.pushVerifiedExecution("nic", "15", "nic.remove", binding);
            try { hook.beforeExecution(child("ipaddress.deactivate", "ipAddress", "16"), binding); }
            finally { engine.popVerifiedExecution(frame); }
        } finally { engine.popVerifiedExecution(parent); }
        assertNull(ApiContext.getContext());
    }

    @Test public void stopMissingParentAndUnrelatedActionsCannotBorrowRemoveAuthority() {
        EngineContext engine = EngineContext.getEngineContext();
        assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(child("nic.remove", "nic", "15"), binding));
        var stop = engine.pushVerifiedExecution("instance", "3", "instance.stop", binding);
        try { assertThrows(ProcessAuthorizationDeniedException.class,
                () -> hook.beforeExecution(child("nic.remove", "nic", "15"), binding)); }
        finally { engine.popVerifiedExecution(stop); }
        var parent = engine.pushVerifiedExecution("instance", "3", "instance.remove", binding);
        try {
            assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(child("nic.purge", "nic", "15"), binding));
            assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(child("ipaddress.deactivate", "ipAddress", "16"), binding));
        } finally { engine.popVerifiedExecution(parent); }
    }

    @Test public void foreignChildOwnerReassignmentAndChangedUuidRemainDenied() {
        EngineContext engine = EngineContext.getEngineContext();
        var parent = engine.pushVerifiedExecution("instance", "3", "instance.remove", binding);
        try {
            nic.setInstanceId(99L);
            assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(child("nic.remove", "nic", "15"), binding));
            nic.setInstanceId(3L); nic.setAccountId(6L);
            assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(child("nic.remove", "nic", "15"), binding));
            nic.setAccountId(5L);
            NicRecord rebound = new NicRecord(); rebound.from(nic); rebound.setUuid("replacement");
            when(hook.objectManager.loadResource(Nic.class, 15L)).thenReturn(rebound);
            assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(child("nic.remove", "nic", "15"), binding));
        } finally { engine.popVerifiedExecution(parent); }
    }

    @Test public void retiredIpPathRejectsForeignOrSharedMappingAndExplicitDenials() {
        EngineContext engine = EngineContext.getEngineContext();
        var parent = engine.pushVerifiedExecution("instance", "3", "instance.remove", binding);
        var frame = engine.pushVerifiedExecution("nic", "15", "nic.remove", binding);
        try {
            map.setNicId(99L);
            assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(child("ipaddress.deactivate", "ipAddress", "16"), binding));
            map.setNicId(15L);
            ip.setAccountId(6L);
            assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(child("ipaddress.deactivate", "ipAddress", "16"), binding));
            ip.setAccountId(5L);
            var shared = new IpAddressNicMapRecord(); shared.setId(18L); shared.setUuid("shared-map");
            shared.setNicId(99L); shared.setIpAddressId(16L); shared.setState("active");
            when(hook.objectManager.loadResource(IpAddressNicMap.class, 18L)).thenReturn(shared);
            when(hook.objectManager.find(IpAddressNicMap.class, IP_ADDRESS_NIC_MAP.IP_ADDRESS_ID, 16L,
                    IP_ADDRESS_NIC_MAP.REMOVED, null)).thenReturn(List.of(map, shared));
            assertThrows(ProcessAuthorizationDeniedException.class, () -> hook.beforeExecution(child("ipaddress.deactivate", "ipAddress", "16"), binding));
            when(hook.objectManager.find(IpAddressNicMap.class, IP_ADDRESS_NIC_MAP.IP_ADDRESS_ID, 16L,
                    IP_ADDRESS_NIC_MAP.REMOVED, null)).thenReturn(List.of(map));
            for (var scope : List.of(ApiKeyPolicy.Scope.resource("nic", "1n15"), ApiKeyPolicy.Scope.resource("ipAddress", "1ip16"),
                    ApiKeyPolicy.Scope.resource("ipAddressNicMap", "1ipm17"))) {
                policy(List.of(new ApiKeyPolicy.Rule("deny-child", ApiKeyPolicy.Effect.DENY, scope, Set.of("delete"))));
                assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                        () -> hook.beforeExecution(child("ipaddress.deactivate", "ipAddress", "16"), binding)).getCode());
            }
        } finally { engine.popVerifiedExecution(frame); engine.popVerifiedExecution(parent); }
    }

    @Test public void queuedWorkerRechecksKeyRevisionRevocationAndCurrentOwnerRole() {
        EngineContext engine = EngineContext.getEngineContext();
        var parent = engine.pushVerifiedExecution("instance", "3", "instance.remove", binding);
        try {
            key.setState("inactive");
            assertEquals("ApiKeyRevoked", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(child("nic.remove", "nic", "15"), binding)).getCode());
            key.setState("active"); key.setData(new ApiKeyPolicyCodec().store(key, new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED,
                    ApiKeyPolicy.Effect.DENY, null, List.of()), 2));
            assertEquals("ApiKeyPolicyChanged", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(child("nic.remove", "nic", "15"), binding)).getCode());
            policy(List.of()); when(schema.getResourceMethods()).thenReturn(List.of());
            assertEquals("OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(child("nic.remove", "nic", "15"), binding)).getCode());
        } finally { engine.popVerifiedExecution(parent); }
    }

    @Test public void liveAncestryAndFullLegacyRetainTheirExistingPaths() {
        nic.setRemoved(null); nic.setState("inactive");
        hook.beforeExecution(child("nic.remove", "nic", "15"), binding);
        nic.setRemoved(new Date(1)); nic.setState("removed");
        key.setKind("apiKey"); key.setData(Map.of()); binding.put("policyRevision", 0L);
        hook.beforeExecution(child("nic.remove", "nic", "15"), binding);
        key.setKind("apiKeyRestricted"); binding.put("policyRevision", 1L);
        for (ApiKeyPolicy policy : List.of(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()),
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,
                ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE))) {
            key.setData(new ApiKeyPolicyCodec().store(key, policy, 1));
            hook.beforeExecution(child("nic.remove", "nic", "15"), binding);
        }
    }

    @Test public void containerScopeCannotMaskItsActualManagedServiceDenial() {
        instance.setServiceId(9L);
        ServiceRecord service = new ServiceRecord(); service.setId(9L); service.setAccountId(5L); service.setState("inactive");
        when(hook.objectManager.loadResource(Service.class, 9L)).thenReturn(service);
        policy(List.of(new ApiKeyPolicy.Rule("deny-parent", ApiKeyPolicy.Effect.DENY,
                ApiKeyPolicy.Scope.resource("service", "1s9"), Set.of("delete"))));
        var parent = EngineContext.getEngineContext().pushVerifiedExecution("instance", "3", "instance.remove", binding);
        try {
            assertEquals("KeyPolicyDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                    () -> hook.beforeExecution(child("nic.remove", "nic", "15"), binding)).getCode());
        } finally { EngineContext.getEngineContext().popVerifiedExecution(parent); }
    }
}
