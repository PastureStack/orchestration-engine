package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.api.resource.NamedResourceIdentity;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Image;
import io.cattle.platform.core.model.ImageStoragePoolMap;
import io.cattle.platform.core.model.StoragePool;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorization;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.core.model.ServiceExposeMap;
import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.Volume;
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.Nic;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.model.InstanceLink;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.VolumeStoragePoolMap;
import io.cattle.platform.core.model.IpAddress;
import io.cattle.platform.core.model.IpAddressNicMap;
import io.cattle.platform.core.model.HostIpAddressMap;
import io.cattle.platform.core.model.tables.records.*;
import static io.cattle.platform.core.model.tables.MountTable.MOUNT;
import static io.cattle.platform.core.model.tables.IpAddressNicMapTable.IP_ADDRESS_NIC_MAP;
import static io.cattle.platform.core.model.tables.VolumeStoragePoolMapTable.VOLUME_STORAGE_POOL_MAP;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Map;
import java.util.List;
import java.util.HashMap;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyTargetResolverTest {
    ApiKeyTargetResolver resolver;
    Policy owner;
    Stack stack;
    @Test public void persistedUpgradeLifecycleGraphResolvesOnlyForItsLiveRoots() {
        Lifecycle f = lifecycle();
        io.github.ibuildthecloud.gdapi.context.ApiContext.remove();
        for (Object root : List.of(f.instance, f.service, f.stack)) {
            for (Object child : f.children()) {
                var dependency = resolver.resolveLifecycleDependency(root, child, owner);
                assertEquals("1a5", dependency.scope().projectId());
                assertEquals("1st8", dependency.scope().stackId());
                assertTrue(dependency.resources().contains(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")));
                assertTrue(dependency.resources().contains(ApiKeyPolicyEvaluator.Target.stackResource("service", "1s2", "1a5", "1st8")));
            }
        }
        assertNull(io.github.ibuildthecloud.gdapi.context.ApiContext.getContext());
        // A persisted lifecycle relation does not make an unknown public API type resolvable.
        assertEquals(ApiKeyPolicyEvaluator.TargetLevel.UNRESOLVED, resolver.resolveObject("instanceHostMap", f.hostMap, owner).level());
        assertNull(resolver.resolveLifecycleDependency(f.service, new Object(), owner));
    }
    @Test public void serviceExposeMapLifecycleUsesOnlyItsExactPersistedServiceRoot() {
        for (boolean managed : List.of(true, false)) {
            Lifecycle f = lifecycle();
            f.expose.setManaged(managed); f.expose.setUpgrade(!managed);
            io.github.ibuildthecloud.gdapi.context.ApiContext.remove();
            var dependency = resolver.resolveLifecycleDependency(f.service, f.expose, owner);
            assertEquals("1a5", dependency.scope().projectId());
            assertEquals("1st8", dependency.scope().stackId());
            assertEquals("serviceExposeMap", dependency.scope().resourceType());
            assertTrue(dependency.resources().contains(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")));
            assertTrue(dependency.resources().contains(ApiKeyPolicyEvaluator.Target.stackResource("service", "1s2", "1a5", "1st8")));
            assertTrue(dependency.resources().contains(dependency.scope()));
            assertEquals(ApiKeyPolicyEvaluator.TargetLevel.UNRESOLVED, resolver.resolveObject("serviceExposeMap", f.expose, owner).level());
            assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.instance, f.expose, owner));
            assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.stack, f.expose, owner));
            assertNull(io.github.ibuildthecloud.gdapi.context.ApiContext.getContext());
        }
    }
    @Test public void serviceExposeMapRejectsMissingForeignRemovedOrContradictoryPersistedGraph() {
        for (String invalid : List.of("missing-map", "missing-instance", "missing-service", "missing-stack", "missing-project",
                "foreign-map", "foreign-instance", "foreign-service", "foreign-stack", "removed-map", "removed-instance",
                "removed-service", "removed-stack", "removed-project", "purged-map", "purged-instance", "purged-service", "purged-stack",
                "unmanaged", "null-managed", "null-instance", "null-service", "null-account", "contradictory-stack",
                "fabricated-account", "fabricated-instance", "fabricated-service", "fabricated-managed", "fabricated-upgrade")) {
            Lifecycle f = lifecycle();
            ServiceExposeMapRecord attempted = f.expose;
            switch (invalid) {
                case "missing-map" -> f.rows.get(ServiceExposeMap.class).clear();
                case "missing-instance" -> f.rows.get(Instance.class).clear();
                case "missing-service" -> f.rows.get(Service.class).clear();
                case "missing-stack" -> f.rows.get(Stack.class).clear();
                case "missing-project" -> f.rows.get(Account.class).clear();
                case "foreign-map" -> f.expose.setAccountId(6L);
                case "foreign-instance" -> f.instance.setAccountId(6L);
                case "foreign-service" -> f.service.setAccountId(6L);
                case "foreign-stack" -> f.stack.setAccountId(6L);
                case "removed-map" -> f.expose.setRemoved(new java.util.Date(1));
                case "removed-instance" -> f.instance.setRemoved(new java.util.Date(1));
                case "removed-service" -> f.service.setRemoved(new java.util.Date(1));
                case "removed-stack" -> f.stack.setRemoved(new java.util.Date(1));
                case "removed-project" -> ((Account) f.rows.get(Account.class).get(5L)).setRemoved(new java.util.Date(1));
                case "purged-map" -> f.expose.setState("purged");
                case "purged-instance" -> f.instance.setState("purged");
                case "purged-service" -> f.service.setState("purged");
                case "purged-stack" -> f.stack.setState("purged");
                case "unmanaged" -> f.expose.setUpgrade(false);
                case "null-managed" -> f.expose.setManaged(null);
                case "null-instance" -> f.expose.setInstanceId(null);
                case "null-service" -> f.expose.setServiceId(null);
                case "null-account" -> f.expose.setAccountId(null);
                case "contradictory-stack" -> f.instance.setStackId(9L);
                default -> {
                    attempted = new ServiceExposeMapRecord(); attempted.from(f.expose);
                    switch (invalid) {
                        case "fabricated-account" -> attempted.setAccountId(6L);
                        case "fabricated-instance" -> attempted.setInstanceId(99L);
                        case "fabricated-service" -> attempted.setServiceId(99L);
                        case "fabricated-managed" -> attempted.setManaged(true);
                        case "fabricated-upgrade" -> attempted.setUpgrade(false);
                    }
                }
            }
            ServiceExposeMapRecord child = attempted;
            assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.service, child, owner));
        }
    }
    @Test public void serviceExposeMapCannotBorrowAnotherServiceOnTheSameInstance() {
        Lifecycle f = lifecycle();
        ServiceRecord other = new ServiceRecord(); other.setId(99L); other.setAccountId(5L); other.setStackId(8L);
        f.add(Service.class, other);
        ServiceExposeMapRecord otherMap = new ServiceExposeMapRecord(); otherMap.from(f.expose);
        otherMap.setId(99L); otherMap.setServiceId(99L); otherMap.setManaged(true); otherMap.setUpgrade(false);
        f.add(ServiceExposeMap.class, otherMap);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of(otherMap));
        // Both Services are legitimate parents of this Instance. Neither map may
        // use the other Service's grant merely because that Instance is shared.
        assertNotNull(resolver.resolveLifecycleDependency(f.service, f.expose, owner));
        assertNotNull(resolver.resolveLifecycleDependency(other, otherMap, owner));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.service, otherMap, owner));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(other, f.expose, owner));
    }
    @Test public void serviceExposeMapDependencyRetainsEveryActualChildDenial() {
        Lifecycle f = lifecycle();
        var dependency = resolver.resolveLifecycleDependency(f.service, f.expose, owner);
        for (var denied : List.of(ApiKeyPolicy.Scope.resource("container", "1i3"), ApiKeyPolicy.Scope.resource("service", "1s2"),
                ApiKeyPolicy.Scope.resource("serviceExposeMap", dependency.scope().resourceId()))) {
            ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                    new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), java.util.Set.of("upgrade")),
                    new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, denied, java.util.Set.of("upgrade"))));
            assertEquals(ApiKeyPolicyEvaluator.Reason.POLICY_DENIED, new ApiKeyPolicyEvaluator().evaluateAuthorizedDependency(policy,
                    new ApiKeyPolicyEvaluator.Request(true, "upgrade", true, dependency.resources()), java.time.Instant.EPOCH).reason());
        }
    }
    @Test public void hostMapRejectsMissingForeignUnmanagedRemovedAndContradictoryAncestry() {
        for (String invalid : List.of("missing-map", "missing-instance", "foreign-instance", "removed-map", "removed-instance",
                "unmanaged", "removed-expose-map", "foreign-expose-map", "missing-service", "removed-service", "foreign-service",
                "removed-stack", "foreign-stack", "different-instance-stack", "different-service-stack", "removed-root", "removed-project",
                "contradictory-instance-state", "contradictory-map-state", "fabricated-parent", "fabricated-host")) {
            Lifecycle f = lifecycle();
            Object child = f.hostMap;
            switch (invalid) {
                case "missing-map" -> f.rows.get(InstanceHostMap.class).clear();
                case "missing-instance" -> f.rows.get(Instance.class).clear();
                case "foreign-instance" -> f.instance.setAccountId(6L);
                case "removed-map" -> f.hostMap.setRemoved(new java.util.Date(1));
                case "removed-instance" -> f.instance.setRemoved(new java.util.Date(1));
                case "unmanaged" -> f.expose.setUpgrade(false);
                case "removed-expose-map" -> f.expose.setRemoved(new java.util.Date(1));
                case "foreign-expose-map" -> f.expose.setAccountId(6L);
                case "missing-service" -> f.rows.get(Service.class).clear();
                case "removed-service", "removed-root" -> f.service.setRemoved(new java.util.Date(1));
                case "foreign-service" -> f.service.setAccountId(6L);
                case "removed-stack" -> f.stack.setRemoved(new java.util.Date(1));
                case "foreign-stack" -> f.stack.setAccountId(6L);
                case "removed-project" -> ((Account) f.rows.get(Account.class).get(5L)).setRemoved(new java.util.Date(1));
                case "contradictory-instance-state" -> f.instance.setState("purged");
                case "contradictory-map-state" -> f.hostMap.setState("removed");
                case "different-instance-stack" -> f.instance.setStackId(9L);
                case "different-service-stack" -> f.service.setStackId(9L);
                case "fabricated-parent", "fabricated-host" -> {
                    InstanceHostMapRecord forged = new InstanceHostMapRecord(); forged.from(f.hostMap);
                    if (invalid.equals("fabricated-parent")) forged.setInstanceId(99L); else forged.setHostId(99L);
                    child = forged;
                }
            }
            Object attempted = child;
            assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.service, attempted, owner));
        }
    }
    @Test public void internalChildCannotBorrowUnrelatedServiceOrStackEvenInSameAccount() {
        Lifecycle f = lifecycle();
        ServiceRecord otherService = new ServiceRecord(); otherService.setId(99L); otherService.setAccountId(5L); otherService.setStackId(8L);
        StackRecord otherStack = new StackRecord(); otherStack.setId(9L); otherStack.setAccountId(5L);
        InstanceRecord otherInstance = new InstanceRecord(); otherInstance.setId(99L); otherInstance.setAccountId(5L);
        for (Object root : List.of(otherService, otherStack, otherInstance)) {
            assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(root, f.hostMap, owner));
        }
        ServiceRecord forgedRoot = new ServiceRecord(); forgedRoot.from(f.service); forgedRoot.setStackId(9L);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(forgedRoot, f.hostMap, owner));
    }
    @Test public void sharedVolumeAndAddressCheckEveryPersistedInstanceReference() {
        for (boolean volume : List.of(true, false)) {
            Lifecycle f = lifecycle();
            InstanceRecord foreign = new InstanceRecord(); foreign.setId(99L); foreign.setAccountId(5L); foreign.setStackId(9L);
            StackRecord foreignStack = new StackRecord(); foreignStack.setId(9L); foreignStack.setAccountId(5L);
            f.add(Instance.class, foreign); f.add(Stack.class, foreignStack);
            if (volume) {
                MountRecord shared = new MountRecord(); shared.setId(99L); shared.setAccountId(5L); shared.setInstanceId(99L); shared.setVolumeId(6L);
                f.add(Mount.class, shared);
                assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.stack, f.volume, owner));
                assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.stack, f.poolMap, owner));
            } else {
                NicRecord nic = new NicRecord(); nic.setId(99L); nic.setAccountId(5L); nic.setInstanceId(99L); f.add(Nic.class, nic);
                IpAddressNicMapRecord shared = new IpAddressNicMapRecord(); shared.setId(99L); shared.setNicId(99L); shared.setIpAddressId(10L); f.add(IpAddressNicMap.class, shared);
                assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.stack, f.address, owner));
                assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.stack, f.hostAddressMap, owner));
            }
        }
    }
    @Test public void internalResourcesRequireTheirOwnLiveAccountAndPersistedIntermediateRows() {
        for (String invalid : List.of("foreign-nic", "foreign-port", "foreign-link", "foreign-mount", "foreign-volume", "foreign-address",
                "missing-nic", "missing-volume", "missing-address", "unlinked-address", "contradictory-volume-stack", "unknown-pool-map")) {
            Lifecycle f = lifecycle(); Object child = f.hostMap;
            switch (invalid) {
                case "foreign-nic" -> { f.nic.setAccountId(6L); child = f.nic; }
                case "foreign-port" -> { f.port.setAccountId(6L); child = f.port; }
                case "foreign-link" -> { f.link.setAccountId(6L); child = f.link; }
                case "foreign-mount" -> { f.mount.setAccountId(6L); child = f.mount; }
                case "foreign-volume" -> { f.volume.setAccountId(6L); child = f.poolMap; }
                case "foreign-address" -> { f.address.setAccountId(6L); child = f.addressMap; }
                case "missing-nic" -> { f.rows.get(Nic.class).clear(); child = f.address; }
                case "missing-volume" -> { f.rows.get(Volume.class).clear(); child = f.mount; }
                case "missing-address" -> { f.rows.get(IpAddress.class).clear(); child = f.addressMap; }
                case "unlinked-address" -> { f.rows.get(IpAddressNicMap.class).clear(); child = f.address; }
                case "contradictory-volume-stack" -> { f.volume.setStackId(9L); child = f.poolMap; }
                case "unknown-pool-map" -> { f.rows.get(VolumeStoragePoolMap.class).clear(); child = f.poolMap; }
            }
            Object attempted = child;
            assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.service, attempted, owner));
        }
    }
    @Test public void lifecycleDependenciesPreserveIntermediateVolumeAndServiceDenials() {
        Lifecycle f = lifecycle();
        var dependency = resolver.resolveLifecycleDependency(f.service, f.poolMap, owner);
        for (var denied : List.of(ApiKeyPolicy.Scope.resource("volume", "1v6"), ApiKeyPolicy.Scope.resource("service", "1s2"))) {
            ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                    new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), java.util.Set.of("upgrade")),
                    new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, denied, java.util.Set.of("upgrade"))));
            assertEquals(ApiKeyPolicyEvaluator.Reason.POLICY_DENIED, new ApiKeyPolicyEvaluator().evaluateAuthorizedDependency(policy,
                    new ApiKeyPolicyEvaluator.Request(true, "upgrade", true, dependency.resources()), java.time.Instant.EPOCH).reason());
        }
        var hostDependency = resolver.resolveLifecycleDependency(f.service, f.hostMap, owner);
        ApiKeyPolicy deniedHostMap = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null,
                List.of(new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY,
                        ApiKeyPolicy.Scope.resource("instanceHostMap", hostDependency.scope().resourceId()), java.util.Set.of("upgrade"))));
        assertEquals(ApiKeyPolicyEvaluator.Reason.POLICY_DENIED, new ApiKeyPolicyEvaluator().evaluateAuthorizedDependency(deniedHostMap,
                new ApiKeyPolicyEvaluator.Request(true, "upgrade", true, hostDependency.resources()), java.time.Instant.EPOCH).reason());
    }
    @Test public void directVolumeScopeRetainsItsPublicResolverContract() {
        Lifecycle f = lifecycle(); f.volume.setStackId(8L);
        assertNull(resolver.resolveLifecycleDependency(f.volume, f.volume, owner));
        assertEquals(ApiKeyPolicyEvaluator.Target.stackResource("volume", "1v6", "1a5", "1st8"),
                resolver.resolveObject("volume", f.volume, owner));
    }
    @Test public void liveRemovingRowsAuthorizeBeforePostListenerAndRemovedRowsRemainDenied() {
        Lifecycle f = lifecycle(); f.service.setState("finishing-upgrade"); f.instance.setState("removing");
        // SetRemovedFields is a remove POST listener: these handlers execute with
        // persisted state=removing but removed=NULL, without trusting process names.
        for (Object child : f.children()) {
            io.cattle.platform.object.util.ObjectUtils.setProperty(child, "state", "removing");
            assertNotNull(resolver.resolveLifecycleDependency(f.service, child, owner));
            io.cattle.platform.object.util.ObjectUtils.setProperty(child, "removed", new java.util.Date(1));
            assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.service, child, owner));
            io.cattle.platform.object.util.ObjectUtils.setProperty(child, "removed", null);
        }
        f.instance.setRemoved(new java.util.Date(1));
        for (Object child : f.children()) {
            assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.service, child, owner));
        }
    }
    private Map<String, Object> finishUpgradeBinding() {
        return new HashMap<>(Map.of("targetType", "service", "targetId", "2", "operation", "upgrade",
                "requestAction", "finishupgrade", "requestMethod", "POST", "requestCollection", false, "preview", false));
    }
    private Lifecycle removalLifecycle() {
        Lifecycle f = lifecycle();
        f.instance.setState("removing"); f.expose.setState("removed"); f.expose.setRemoved(new java.util.Date(1));
        f.volume.setDeviceNumber(0); f.volume.setImageId(7L); f.volume.setState("detached");
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.SERVICE_ID, 2L, SERVICE_EXPOSE_MAP.MANAGED, false,
                SERVICE_EXPOSE_MAP.UPGRADE, true, SERVICE_EXPOSE_MAP.STATE, "removed")).thenReturn(List.of(f.expose));
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of());
        return f;
    }
    @Test public void finishUpgradeRetainsExactTombstoneOnlyForRemovingInstanceAndItsOsVolume() {
        Lifecycle f = removalLifecycle(); var binding = finishUpgradeBinding();
        var queued = resolver.resolveUpgradeRemovalDependency(f.service, f.instance, owner, "instance.remove", "instance", null, binding);
        assertEquals(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"), queued.scope());
        // Generic public/lifecycle resolution still does not revive removed maps.
        assertEquals(ApiKeyPolicyEvaluator.Target.projectResource("container", "1i3", "1a5"), resolver.resolveObject("container", f.instance, owner));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.service, f.volume, owner));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.service, f.expose, owner));
        EngineContext engine = EngineContext.getEngineContext();
        var instanceFrame = engine.pushVerifiedExecution("instance", "3", binding);
        try {
            assertNotNull(resolver.resolveUpgradeRemovalDependency(f.service, f.instance, owner, "instance.deallocate", "instance", instanceFrame, binding));
            var volume = resolver.resolveUpgradeRemovalDependency(f.service, f.volume, owner, "volume.remove", "volume", instanceFrame, binding);
            assertEquals(ApiKeyPolicyEvaluator.Target.stackResource("volume", "1v6", "1a5", "1st8"), volume.scope());
            assertTrue(volume.resources().containsAll(queued.resources()));
            assertTrue(volume.resources().stream().anyMatch(t -> "serviceExposeMap".equals(t.resourceType())));
        } finally { engine.popVerifiedExecution(instanceFrame); }
        var volumeFrame = engine.pushVerifiedExecution("volume", "6", binding);
        try { assertNotNull(resolver.resolveUpgradeRemovalDependency(f.service, f.volume, owner, "volume.deallocate", "volume", volumeFrame, binding)); }
        finally { engine.popVerifiedExecution(volumeFrame); }
        f.service.setStackId(null);
        assertEquals(ApiKeyPolicyEvaluator.Target.projectResource("container", "1i3", "1a5"),
                resolver.resolveUpgradeRemovalDependency(f.service, f.instance, owner, "instance.remove", "instance", null, binding).scope());
    }
    @Test public void removalTombstoneReloadRejectsMissingForeignUnmarkedAndContradictoryRows() {
        for (String invalid : List.of("missing-map", "map-id", "map-account", "map-service", "map-instance", "managed", "upgrade",
                "map-live", "map-purged", "instance-account", "instance-service", "instance-stack", "missing-instance",
                "removed-instance", "missing-service", "removed-service", "foreign-stack", "removed-stack", "removed-project")) {
            Lifecycle f = removalLifecycle(); var binding = finishUpgradeBinding();
            switch (invalid) {
                case "missing-map" -> f.rows.get(ServiceExposeMap.class).clear();
                case "map-id" -> f.expose.setId(99L);
                case "map-account" -> f.expose.setAccountId(6L);
                case "map-service" -> f.expose.setServiceId(99L);
                case "map-instance" -> f.expose.setInstanceId(99L);
                case "managed" -> f.expose.setManaged(true);
                case "upgrade" -> f.expose.setUpgrade(false);
                case "map-live" -> f.expose.setRemoved(null);
                case "map-purged" -> f.expose.setState("purged");
                case "instance-account" -> f.instance.setAccountId(6L);
                case "instance-service" -> f.instance.setServiceId(99L);
                case "instance-stack" -> f.instance.setStackId(99L);
                case "missing-instance" -> f.rows.get(Instance.class).clear();
                case "removed-instance" -> f.instance.setRemoved(new java.util.Date(1));
                case "missing-service" -> f.rows.get(Service.class).clear();
                case "removed-service" -> f.service.setRemoved(new java.util.Date(1));
                case "foreign-stack" -> f.stack.setAccountId(6L);
                case "removed-stack" -> f.stack.setRemoved(new java.util.Date(1));
                case "removed-project" -> ((Account) f.rows.get(Account.class).get(5L)).setRemoved(new java.util.Date(1));
            }
            assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveUpgradeRemovalDependency(
                    f.service, f.instance, owner, "instance.remove", "instance", null, binding));
        }
        Lifecycle f = removalLifecycle(); var binding = finishUpgradeBinding();
        ServiceExposeMapRecord forged = new ServiceExposeMapRecord(); forged.from(f.expose); forged.setRemoved(new java.util.Date(2));
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.SERVICE_ID, 2L, SERVICE_EXPOSE_MAP.MANAGED, false,
                SERVICE_EXPOSE_MAP.UPGRADE, true, SERVICE_EXPOSE_MAP.STATE, "removed")).thenReturn(List.of(forged));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeRemovalDependency(
                f.service, f.instance, owner, "instance.remove", "instance", null, binding));
    }
    @Test public void removalCannotBorrowTombstoneAfterLiveInstanceReassignment() {
        Lifecycle f = removalLifecycle(); var binding = finishUpgradeBinding();
        ServiceExposeMapRecord other = new ServiceExposeMapRecord(); other.from(f.expose);
        other.setId(99L); other.setServiceId(99L); other.setRemoved(null); other.setState("active"); f.add(ServiceExposeMap.class, other);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null)).thenReturn(List.of(other));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeRemovalDependency(
                f.service, f.instance, owner, "instance.remove", "instance", null, binding));
        other.setServiceId(2L); other.setManaged(true); other.setUpgrade(false);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeRemovalDependency(
                f.service, f.instance, owner, "instance.remove", "instance", null, binding));
    }
    @Test public void removalBindingAndCurrentFrameCannotBeReconstructedFromANameOrPayload() {
        for (String field : List.of("targetType", "targetId", "operation", "requestAction", "requestMethod", "requestCollection", "preview")) {
            Lifecycle f = removalLifecycle(); var binding = finishUpgradeBinding(); binding.remove(field);
            assertThrows(field, ClientVisibleException.class, () -> resolver.resolveUpgradeRemovalDependency(
                    f.service, f.instance, owner, "instance.remove", "instance", null, binding));
        }
        Lifecycle f = removalLifecycle(); var binding = finishUpgradeBinding();
        assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeRemovalDependency(f.service, f.volume, owner, "volume.remove", "volume", null, binding));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeRemovalDependency(f.service, f.instance, owner, "instance.deallocate", "instance", null, binding));
        EngineContext engine = EngineContext.getEngineContext();
        for (String invalid : List.of("instance-id", "resource-type", "root-binding")) {
            var frame = engine.pushVerifiedExecution(invalid.equals("resource-type") ? "service" : "instance",
                    invalid.equals("instance-id") ? "99" : "3", invalid.equals("root-binding") ? Map.of("targetId", "99") : binding);
            try { assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveUpgradeRemovalDependency(
                    f.service, f.volume, owner, "volume.remove", "volume", frame, binding)); }
            finally { engine.popVerifiedExecution(frame); }
        }
        assertNull(resolver.resolveUpgradeRemovalDependency(f.service, f.instance, owner, "instance.stop", "instance", null, binding));
        assertNull(resolver.resolveUpgradeRemovalDependency(f.service, f.instance, owner, "instance.remove", "volume", null, binding));
        assertNull(resolver.resolveUpgradeRemovalDependency(f.service, f.volume, owner, "volume.activate", "volume", null, binding));
        assertNull(resolver.resolveUpgradeRemovalDependency(f.stack, f.instance, owner, "instance.remove", "instance", null, binding));
    }
    @Test public void osVolumeRemovalRejectsForeignDeviceAndOutsideMountOrForgedPersistedReferences() {
        for (String invalid : List.of("account", "device", "instance", "stack", "removed", "outside-mount", "foreign-mount", "forged-image")) {
            Lifecycle f = removalLifecycle(); var binding = finishUpgradeBinding(); VolumeRecord attempted = f.volume;
            switch (invalid) {
                case "account" -> f.volume.setAccountId(6L);
                case "device" -> f.volume.setDeviceNumber(1);
                case "instance" -> f.volume.setInstanceId(99L);
                case "stack" -> f.volume.setStackId(99L);
                case "removed" -> f.volume.setRemoved(new java.util.Date(1));
                case "outside-mount" -> f.mount.setInstanceId(99L);
                case "foreign-mount" -> f.mount.setAccountId(6L);
                case "forged-image" -> { attempted = new VolumeRecord(); attempted.from(f.volume); attempted.setImageId(99L); }
            }
            VolumeRecord resource = attempted;
            EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("instance", "3", binding);
            try { assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveUpgradeRemovalDependency(
                    f.service, resource, owner, "volume.remove", "volume", frame, binding)); }
            finally { engine.popVerifiedExecution(frame); }
        }
    }
    @Test public void removalStillRequiresOwnerAccessAndPreservesEveryDependencyDenial() {
        Lifecycle f = removalLifecycle(); var binding = finishUpgradeBinding();
        for (Object blocked : List.of(f.instance, f.service, f.expose, f.stack, f.volume)) {
            Policy live = mock(Policy.class);
            when(live.authorizeObject(any())).thenAnswer(i -> i.getArgument(0) == blocked ? null : i.getArgument(0));
            EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("instance", "3", binding);
            try { assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeRemovalDependency(
                    f.service, f.volume, live, "volume.remove", "volume", frame, binding)); }
            finally { engine.popVerifiedExecution(frame); }
        }
        var dependency = resolver.resolveUpgradeRemovalDependency(f.service, f.instance, owner, "instance.remove", "instance", null, binding);
        for (var target : dependency.resources()) {
            ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                    new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("service", "1s2"), java.util.Set.of("upgrade")),
                    new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource(target.resourceType(), target.resourceId()), java.util.Set.of("upgrade"))));
            assertEquals(ApiKeyPolicyEvaluator.Reason.POLICY_DENIED, new ApiKeyPolicyEvaluator().evaluateAuthorizedDependency(policy,
                    new ApiKeyPolicyEvaluator.Request(true, "upgrade", true, dependency.resources()), java.time.Instant.EPOCH).reason());
        }
    }
    private Lifecycle cleanupLifecycle() {
        Lifecycle f = removalLifecycle(); f.volume.setState("removing"); f.volume.setUuid("private-volume");
        f.instance.setImageId(7L); f.poolMap.setState("inactive"); f.poolMap.setUuid("private-allocation");
        ImageRecord image = new ImageRecord(); image.setId(7L); image.setUuid("private-image"); image.setFormat("docker");
        image.setInstanceKind("container"); image.setState("active"); f.add(Image.class, image);
        ImageStoragePoolMapRecord cache = new ImageStoragePoolMapRecord(); cache.setId(14L); cache.setUuid("private-cache");
        cache.setImageId(7L); cache.setStoragePoolId(9L); cache.setState("active"); f.add(ImageStoragePoolMap.class, cache);
        StoragePoolRecord pool = new StoragePoolRecord(); pool.setId(9L); pool.setAccountId(5L); pool.setUuid("pool-nine"); f.add(StoragePool.class, pool);
        HostRecord host = new HostRecord(); host.setId(9L); host.setAccountId(5L); f.add(io.cattle.platform.core.model.Host.class, host);
        when(resolver.objectManager.find(VolumeStoragePoolMap.class, VOLUME_STORAGE_POOL_MAP.VOLUME_ID, 6L)).thenAnswer(i ->
                f.rows.get(VolumeStoragePoolMap.class).values().stream().map(VolumeStoragePoolMap.class::cast).toList());
        when(resolver.objectManager.find(ImageStoragePoolMap.class,
                io.cattle.platform.core.model.tables.ImageStoragePoolMapTable.IMAGE_STORAGE_POOL_MAP.IMAGE_ID, 7L)).thenAnswer(i ->
                f.rows.get(ImageStoragePoolMap.class).values().stream().map(ImageStoragePoolMap.class::cast).toList());
        when(resolver.objectManager.find(Volume.class, io.cattle.platform.core.model.tables.VolumeTable.VOLUME.IMAGE_ID, 7L,
                io.cattle.platform.core.model.tables.VolumeTable.VOLUME.REMOVED, null)).thenAnswer(i ->
                f.rows.get(Volume.class).values().stream().map(Volume.class::cast).filter(v -> v.getRemoved() == null && Long.valueOf(7).equals(v.getImageId())).toList());
        when(resolver.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null)).thenAnswer(i ->
                f.rows.get(Instance.class).values().stream().map(Instance.class::cast).filter(v -> v.getRemoved() == null && Long.valueOf(7).equals(v.getImageId())).toList());
        return f;
    }
    private LaunchConfiguration cleanupJob(String type, String action, long id) {
        return new LaunchConfiguration(type.toLowerCase() + "." + action, type, Long.toString(id), 5L, 0, new HashMap<>());
    }
    private ProcessRecord queuedMap(Lifecycle f, Map<String, Object> binding) {
        EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(binding);
        var frame = engine.pushVerifiedExecution("volume", "6", binding);
        try {
            LaunchConfiguration config = cleanupJob("volumeStoragePoolMap", "remove", 12);
            ProcessAuthorization.prepare(config, List.of());
            ProcessRecord record = new ProcessRecord(config, 99L, "server");
            assertNotNull(resolver.resolveUpgradeCleanupDependency(f.service, f.poolMap, owner, record, frame, null, binding));
            return record;
        } finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
    }
    @Test public void exactImageAndAllPrivateCachePoolsCleanUpOnlyUnderVerifiedVolume() {
        Lifecycle f = cleanupLifecycle(); var binding = finishUpgradeBinding(); EngineContext engine = EngineContext.getEngineContext();
        var volume = engine.pushVerifiedExecution("volume", "6", binding);
        try {
            Image image = (Image) f.rows.get(Image.class).get(7L);
            for (String action : List.of("deactivate", "remove")) {
                var dependency = resolver.resolveUpgradeCleanupDependency(f.service, image, owner, cleanupJob("image", action, 7), volume, null, binding);
                assertEquals("volume", dependency.scope().resourceType());
                assertTrue(dependency.resources().stream().anyMatch(t -> "image".equals(t.resourceType()) && "1img7".equals(t.resourceId())));
            }
            var imageFrame = engine.pushVerifiedExecution("image", "7", binding);
            try {
                ImageStoragePoolMap cache = (ImageStoragePoolMap) f.rows.get(ImageStoragePoolMap.class).get(14L);
                for (String action : List.of("deactivate", "remove"))
                    assertNotNull(resolver.resolveUpgradeCleanupDependency(f.service, cache, owner,
                            cleanupJob("imageStoragePoolMap", action, 14), imageFrame, volume, binding));
            } finally { engine.popVerifiedExecution(imageFrame); }
            // Removed allocation history remains private relationship evidence.
            f.poolMap.setState("removed"); f.poolMap.setRemoved(new java.util.Date(1));
            assertNotNull(resolver.resolveUpgradeCleanupDependency(f.service, image, owner, cleanupJob("image", "remove", 7), volume, null, binding));
            assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("image", image, owner));
        } finally { engine.popVerifiedExecution(volume); }
    }
    @Test public void queuedMapRemoveRetainsExactPinAfterOwnedParentsAreRemovedWithoutFakeFrame() {
        Lifecycle f = cleanupLifecycle(); var binding = finishUpgradeBinding(); ProcessRecord record = queuedMap(f, binding);
        f.volume.setState("removed"); f.volume.setRemoved(new java.util.Date(1));
        f.instance.setState("removed"); f.instance.setRemoved(new java.util.Date(1));
        assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
        assertNotNull(resolver.resolveUpgradeCleanupDependency(f.service, f.poolMap, owner, record, null, null, binding));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveLifecycleDependency(f.service, f.poolMap, owner));
    }
    @Test public void queuedMapRejectsPoolDriftRebuiltRowsAndUnverifiedRetryPayload() {
        for (String invalid : List.of("pool", "map-uuid", "volume-uuid", "pool-uuid", "child", "process", "root", "payload")) {
            Lifecycle f = cleanupLifecycle(); var binding = finishUpgradeBinding(); ProcessRecord record = queuedMap(f, binding);
            switch (invalid) {
                case "pool" -> { StoragePoolRecord pool = new StoragePoolRecord(); pool.setId(10L); pool.setAccountId(5L); pool.setUuid("pool-ten"); f.add(StoragePool.class, pool); f.poolMap.setStoragePoolId(10L); }
                case "map-uuid" -> f.poolMap.setUuid("rebuilt");
                case "volume-uuid" -> f.volume.setUuid("rebuilt");
                case "pool-uuid" -> ((StoragePool) f.rows.get(StoragePool.class).get(9L)).setUuid("rebuilt");
                case "child" -> record.setResourceId("13");
                case "process" -> record.setProcessName("volumestoragepoolmap.deactivate");
                case "root" -> binding.put("targetId", "99");
                case "payload" -> { LaunchConfiguration raw = cleanupJob("volumeStoragePoolMap", "remove", 12); raw.setData(new HashMap<>(record.getData())); ProcessAuthorization.prepare(raw, List.of()); record = new ProcessRecord(raw, 100L, "server"); }
            }
            ProcessRecord attempted = record;
            assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveUpgradeCleanupDependency(f.service, f.poolMap, owner, attempted, null, null, binding));
        }
    }
    @Test public void imageCleanupRejectsSharedForeignDriftedOrUnknownRelations() {
        for (String invalid : List.of("other-volume", "other-instance", "image-id", "image-owner", "image-format", "image-registry", "missing-image",
                "missing-allocation", "foreign-pool", "unallocated-cache-pool", "missing-volume", "removed-owner", "removed-service", "outside-mount")) {
            Lifecycle f = cleanupLifecycle(); var binding = finishUpgradeBinding(); Image image = (Image) f.rows.get(Image.class).get(7L);
            switch (invalid) {
                case "other-volume" -> { VolumeRecord other = new VolumeRecord(); other.setId(99L); other.setAccountId(5L); other.setImageId(7L); f.add(Volume.class, other); }
                case "other-instance" -> { InstanceRecord other = new InstanceRecord(); other.setId(99L); other.setAccountId(5L); other.setImageId(7L); f.add(Instance.class, other); }
                case "image-id" -> f.instance.setImageId(99L);
                case "image-owner" -> image.setAccountId(6L);
                case "image-format" -> image.setFormat("other");
                case "image-registry" -> image.setRegistryCredentialId(99L);
                case "missing-image" -> f.rows.get(Image.class).clear();
                case "missing-allocation" -> f.rows.get(VolumeStoragePoolMap.class).clear();
                case "foreign-pool" -> ((StoragePool) f.rows.get(StoragePool.class).get(9L)).setAccountId(6L);
                case "unallocated-cache-pool" -> ((ImageStoragePoolMap) f.rows.get(ImageStoragePoolMap.class).get(14L)).setStoragePoolId(10L);
                case "missing-volume" -> f.rows.get(Volume.class).clear();
                case "removed-owner" -> ((Account) f.rows.get(Account.class).get(5L)).setRemoved(new java.util.Date(1));
                case "removed-service" -> f.service.setRemoved(new java.util.Date(1));
                case "outside-mount" -> f.mount.setInstanceId(99L);
            }
            EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("volume", "6", binding);
            try { assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveUpgradeCleanupDependency(f.service, image, owner,
                    cleanupJob("image", "remove", 7), frame, null, binding)); }
            finally { engine.popVerifiedExecution(frame); }
        }
    }
    @Test public void imageCleanupNeedsBothActualFramesAndPreservesEveryExplicitDenial() {
        Lifecycle f = cleanupLifecycle(); var binding = finishUpgradeBinding(); Image image = (Image) f.rows.get(Image.class).get(7L);
        ImageStoragePoolMap cache = (ImageStoragePoolMap) f.rows.get(ImageStoragePoolMap.class).get(14L);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeCleanupDependency(f.service, image, owner, cleanupJob("image", "remove", 7), null, null, binding));
        EngineContext engine = EngineContext.getEngineContext(); var volume = engine.pushVerifiedExecution("volume", "6", binding);
        try {
            assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeCleanupDependency(f.service, cache, owner,
                    cleanupJob("imageStoragePoolMap", "remove", 14), volume, null, binding));
            var dependency = resolver.resolveUpgradeCleanupDependency(f.service, image, owner, cleanupJob("image", "remove", 7), volume, null, binding);
            for (var target : dependency.resources()) {
                ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                        new ApiKeyPolicy.Rule("allow", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.resource("service", "1s2"), java.util.Set.of("upgrade")),
                        new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource(target.resourceType(), target.resourceId()), java.util.Set.of("upgrade"))));
                assertFalse(new ApiKeyPolicyEvaluator().evaluateAuthorizedDependency(policy,
                        new ApiKeyPolicyEvaluator.Request(true, "upgrade", true, dependency.resources()), java.time.Instant.EPOCH).allowed());
            }
            assertNull(resolver.resolveUpgradeCleanupDependency(f.service, image, owner, cleanupJob("image", "purge", 7), volume, null, binding));
        } finally { engine.popVerifiedExecution(volume); }
    }
    @Test public void fullOwnedContainerAndVolumeImageCleanupDoesNotRequireCustomServiceTombstone() {
        Lifecycle f = cleanupLifecycle(); EngineContext engine = EngineContext.getEngineContext();
        for (Object root : List.of(f.instance, f.volume)) {
            var binding = new HashMap<String, Object>(Map.of("targetType", root == f.instance ? "container" : "volume", "targetId", root == f.instance ? "3" : "6", "operation", "remove"));
            var frame = engine.pushVerifiedExecution("volume", "6", binding);
            try {
                Image image = (Image) f.rows.get(Image.class).get(7L);
                assertNotNull(resolver.resolveOwnedImageCleanupDependency(root, image, owner, cleanupJob("image", "remove", 7), frame, null, binding));
                assertNull(resolver.resolveUpgradeCleanupDependency(root, image, owner, cleanupJob("image", "remove", 7), frame, null, binding));
                assertNull(resolver.resolveOwnedImageCleanupDependency(image, image, owner, cleanupJob("image", "remove", 7), frame, null, binding));
            } finally { engine.popVerifiedExecution(frame); }
        }
    }
    @Test public void everyImageCachePoolMustHaveItsOwnAllocationHistoryAndActualParentFrames() {
        Lifecycle f = cleanupLifecycle(); var binding = finishUpgradeBinding();
        StoragePoolRecord pool = new StoragePoolRecord(); pool.setId(10L); pool.setAccountId(5L); pool.setUuid("pool-ten"); f.add(StoragePool.class, pool);
        VolumeStoragePoolMapRecord allocation = new VolumeStoragePoolMapRecord(); allocation.setId(22L); allocation.setVolumeId(6L);
        allocation.setStoragePoolId(10L); allocation.setUuid("allocation-two"); allocation.setState("removed"); allocation.setRemoved(new java.util.Date(1)); f.add(VolumeStoragePoolMap.class, allocation);
        ImageStoragePoolMapRecord cache = new ImageStoragePoolMapRecord(); cache.setId(24L); cache.setImageId(7L); cache.setStoragePoolId(10L);
        cache.setUuid("cache-two"); cache.setState("inactive"); f.add(ImageStoragePoolMap.class, cache);
        EngineContext engine = EngineContext.getEngineContext(); var volume = engine.pushVerifiedExecution("volume", "6", binding);
        var image = engine.pushVerifiedExecution("image", "7", binding);
        try {
            var dependency = resolver.resolveUpgradeCleanupDependency(f.service, cache, owner, cleanupJob("imageStoragePoolMap", "remove", 24), image, volume, binding);
            assertEquals(2, dependency.resources().stream().filter(t -> "imageStoragePoolMap".equals(t.resourceType())).count());
            assertEquals(2, dependency.resources().stream().filter(t -> "storagePool".equals(t.resourceType())).count());
            f.rows.get(VolumeStoragePoolMap.class).remove(22L);
            assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeCleanupDependency(f.service, cache, owner,
                    cleanupJob("imageStoragePoolMap", "remove", 24), image, volume, binding));
            f.add(VolumeStoragePoolMap.class, allocation);
            var wrongParent = engine.pushVerifiedExecution("volume", "99", binding);
            try { assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeCleanupDependency(f.service, cache, owner,
                    cleanupJob("imageStoragePoolMap", "remove", 24), image, wrongParent, binding)); }
            finally { engine.popVerifiedExecution(wrongParent); }
        } finally { engine.popVerifiedExecution(image); engine.popVerifiedExecution(volume); }
    }
    @Test public void exactHostAndVolumeMapsUseTheirRealParentAndDoNotGrantOtherCleanupNames() {
        Lifecycle f = cleanupLifecycle(); var binding = finishUpgradeBinding(); EngineContext engine = EngineContext.getEngineContext();
        var instance = engine.pushVerifiedExecution("instance", "3", binding);
        try {
            for (String action : List.of("deactivate", "remove"))
                assertNotNull(resolver.resolveUpgradeCleanupDependency(f.service, f.hostMap, owner,
                        cleanupJob("instanceHostMap", action, 4), instance, null, binding));
            assertThrows(ClientVisibleException.class, () -> resolver.resolveUpgradeCleanupDependency(f.service, f.poolMap, owner,
                    cleanupJob("volumeStoragePoolMap", "remove", 12), instance, null, binding));
        } finally { engine.popVerifiedExecution(instance); }
        var volume = engine.pushVerifiedExecution("volume", "6", binding);
        try {
            assertNotNull(resolver.resolveUpgradeCleanupDependency(f.service, f.poolMap, owner,
                    cleanupJob("volumeStoragePoolMap", "deactivate", 12), volume, null, binding));
            for (String invalid : List.of("update", "purge", "activate"))
                assertNull(resolver.resolveUpgradeCleanupDependency(f.service, f.poolMap, owner,
                        cleanupJob("volumeStoragePoolMap", invalid, 12), volume, null, binding));
        } finally { engine.popVerifiedExecution(volume); }
    }
    @Test public void ordinaryLiveUpgradeMappingsDoNotGainANewVerifiedFrameRequirement() {
        Lifecycle f = cleanupLifecycle(); f.expose.setRemoved(null); f.expose.setState("active"); var binding = finishUpgradeBinding();
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.SERVICE_ID, 2L, SERVICE_EXPOSE_MAP.MANAGED, false,
                SERVICE_EXPOSE_MAP.UPGRADE, true, SERVICE_EXPOSE_MAP.STATE, "removed")).thenReturn(List.of());
        for (String action : List.of("upgrade", "finishupgrade")) {
            binding.put("requestAction", action);
            assertNull(resolver.resolveUpgradeCleanupDependency(f.service, f.hostMap, owner,
                    cleanupJob("instanceHostMap", "deactivate", 4), null, null, binding));
            assertNull(resolver.resolveUpgradeCleanupDependency(f.service, f.poolMap, owner,
                    cleanupJob("volumeStoragePoolMap", "remove", 12), null, null, binding));
            assertNotNull(resolver.resolveLifecycleDependency(f.service, f.hostMap, owner));
            assertNotNull(resolver.resolveLifecycleDependency(f.service, f.poolMap, owner));
        }
    }
    @Test public void fullPrivateCleanupStillRejectsForeignRootSharedImageAndDirectPublicImage() {
        for (String invalid : List.of("root-id", "root-account", "outside-volume", "direct-image", "no-frame")) {
            Lifecycle f = cleanupLifecycle(); var binding = new HashMap<String, Object>(Map.of("targetType", "container", "targetId", "3", "operation", "remove"));
            Image image = (Image) f.rows.get(Image.class).get(7L); Object root = f.instance;
            switch (invalid) {
                case "root-id" -> binding.put("targetId", "99");
                case "root-account" -> f.instance.setAccountId(6L);
                case "outside-volume" -> { VolumeRecord other = new VolumeRecord(); other.setId(99L); other.setAccountId(5L); other.setImageId(7L); f.add(Volume.class, other); }
                case "direct-image" -> root = image;
            }
            EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("volume", "6", binding); Object attemptedRoot = root;
            try {
                if (invalid.equals("direct-image")) assertNull(resolver.resolveOwnedImageCleanupDependency(root, image, owner,
                        cleanupJob("image", "remove", 7), frame, null, binding));
                else assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveOwnedImageCleanupDependency(attemptedRoot, image, owner,
                        cleanupJob("image", "remove", 7), invalid.equals("no-frame") ? null : frame, null, binding));
            } finally { engine.popVerifiedExecution(frame); }
        }
    }
    private Lifecycle lifecycle() {
        Lifecycle f = new Lifecycle();
        DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(mock(SchemaFactory.class));
        formatter.setTypeMappings(Map.of("project", "a", "stack", "st", "service", "s", "container", "i", "volume", "v", "image", "img"));
        resolver.idFormatter = formatter;
        AccountRecord project = new AccountRecord(); project.setId(5L); project.setKind("project");
        AccountRecord principal = new AccountRecord(); principal.setId(10L);
        owner = new AccountPolicy(project, principal, java.util.Set.of(), new NoPolicyOptions());
        when(resolver.objectManager.loadResource(any(Class.class), any(Long.class))).thenAnswer(i -> {
            Map<Long, Object> table = f.rows.get(i.getArgument(0)); return table == null ? null : table.get(i.getArgument(1));
        });
        f.add(Account.class, project);
        f.instance.setId(3L); f.instance.setAccountId(5L); f.add(Instance.class, f.instance);
        f.service.setId(2L); f.service.setAccountId(5L); f.service.setStackId(8L); f.add(Service.class, f.service);
        f.stack.setId(8L); f.stack.setAccountId(5L); f.add(Stack.class, f.stack);
        f.expose.setId(1L); f.expose.setAccountId(5L); f.expose.setServiceId(2L); f.expose.setInstanceId(3L); f.expose.setManaged(false); f.expose.setUpgrade(true);
        f.add(ServiceExposeMap.class, f.expose);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenAnswer(i ->
                f.expose.getRemoved() == null && Boolean.TRUE.equals(f.expose.getManaged()) ? List.of(f.expose) : List.of());
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true)).thenAnswer(i ->
                f.expose.getRemoved() == null && Boolean.FALSE.equals(f.expose.getManaged()) && Boolean.TRUE.equals(f.expose.getUpgrade()) ? List.of(f.expose) : List.of());
        f.hostMap.setId(4L); f.hostMap.setInstanceId(3L); f.hostMap.setHostId(9L); f.add(InstanceHostMap.class, f.hostMap);
        f.nic.setId(5L); f.nic.setAccountId(5L); f.nic.setInstanceId(3L); f.add(Nic.class, f.nic);
        f.volume.setId(6L); f.volume.setAccountId(5L); f.volume.setInstanceId(3L); f.add(Volume.class, f.volume);
        f.mount.setId(7L); f.mount.setAccountId(5L); f.mount.setInstanceId(3L); f.mount.setVolumeId(6L); f.add(Mount.class, f.mount);
        f.port.setId(8L); f.port.setAccountId(5L); f.port.setInstanceId(3L); f.add(Port.class, f.port);
        f.link.setId(9L); f.link.setAccountId(5L); f.link.setInstanceId(3L); f.add(InstanceLink.class, f.link);
        f.address.setId(10L); f.address.setAccountId(5L); f.add(IpAddress.class, f.address);
        f.addressMap.setId(11L); f.addressMap.setIpAddressId(10L); f.addressMap.setNicId(5L); f.add(IpAddressNicMap.class, f.addressMap);
        f.poolMap.setId(12L); f.poolMap.setVolumeId(6L); f.poolMap.setStoragePoolId(9L); f.add(VolumeStoragePoolMap.class, f.poolMap);
        f.hostAddressMap.setId(13L); f.hostAddressMap.setIpAddressId(10L); f.hostAddressMap.setHostId(9L); f.add(HostIpAddressMap.class, f.hostAddressMap);
        when(resolver.objectManager.find(Mount.class, MOUNT.VOLUME_ID, 6L, MOUNT.REMOVED, null)).thenAnswer(i ->
                f.rows.get(Mount.class).values().stream().map(Mount.class::cast).filter(m -> m.getRemoved() == null && Long.valueOf(6L).equals(m.getVolumeId())).toList());
        when(resolver.objectManager.find(IpAddressNicMap.class, IP_ADDRESS_NIC_MAP.IP_ADDRESS_ID, 10L, IP_ADDRESS_NIC_MAP.REMOVED, null)).thenAnswer(i ->
                f.rows.get(IpAddressNicMap.class).values().stream().map(IpAddressNicMap.class::cast).filter(m -> m.getRemoved() == null && Long.valueOf(10L).equals(m.getIpAddressId())).toList());
        return f;
    }
    private static class Lifecycle {
        final Map<Class<?>, Map<Long, Object>> rows = new HashMap<>();
        final InstanceRecord instance = new InstanceRecord(); final ServiceRecord service = new ServiceRecord(); final StackRecord stack = new StackRecord();
        final ServiceExposeMapRecord expose = new ServiceExposeMapRecord(); final InstanceHostMapRecord hostMap = new InstanceHostMapRecord();
        final NicRecord nic = new NicRecord(); final PortRecord port = new PortRecord(); final InstanceLinkRecord link = new InstanceLinkRecord();
        final VolumeRecord volume = new VolumeRecord(); final MountRecord mount = new MountRecord(); final VolumeStoragePoolMapRecord poolMap = new VolumeStoragePoolMapRecord();
        final IpAddressRecord address = new IpAddressRecord(); final IpAddressNicMapRecord addressMap = new IpAddressNicMapRecord(); final HostIpAddressMapRecord hostAddressMap = new HostIpAddressMapRecord();
        void add(Class<?> type, Object row) {
            Long id = (Long) io.cattle.platform.object.util.ObjectUtils.getPropertyIgnoreErrors(row, "id");
            rows.computeIfAbsent(type, ignored -> new HashMap<>()).put(id, row);
        }
        List<Object> children() { return List.of(hostMap, nic, port, link, volume, mount, poolMap, address, addressMap, hostAddressMap); }
    }
    private static final Map<String, Object> ENSURE_BINDING = Map.of("keyId", "1c1", "requestId", "request1", "targetType", "service", "targetId", "2");
    private record ImageEnsure(Lifecycle lifecycle, ImageRecord image, ImageStoragePoolMapRecord cache, StoragePoolRecord pool) { }

    private ImageEnsure imageEnsure() {
        Lifecycle f = lifecycle();
        ImageRecord image = new ImageRecord(); image.setId(7L); image.setState("active"); f.add(Image.class, image);
        f.volume.setImageId(7L); f.instance.setImageId(7L);
        StoragePoolRecord pool = new StoragePoolRecord(); pool.setId(9L); pool.setAccountId(5L); pool.setState("active"); f.add(StoragePool.class, pool);
        ImageStoragePoolMapRecord cache = new ImageStoragePoolMapRecord(); cache.setId(14L); cache.setImageId(7L); cache.setStoragePoolId(9L);
        f.add(ImageStoragePoolMap.class, cache);
        when(resolver.objectManager.find(VolumeStoragePoolMap.class, VOLUME_STORAGE_POOL_MAP.VOLUME_ID, 6L,
                VOLUME_STORAGE_POOL_MAP.REMOVED, null)).thenAnswer(i -> f.rows.get(VolumeStoragePoolMap.class).values().stream()
                .map(VolumeStoragePoolMap.class::cast).filter(m -> m.getRemoved() == null && Long.valueOf(6L).equals(m.getVolumeId())).toList());
        when(resolver.objectManager.find(VolumeStoragePoolMap.class, VOLUME_STORAGE_POOL_MAP.VOLUME_ID, 6L,
                VOLUME_STORAGE_POOL_MAP.STORAGE_POOL_ID, 9L, VOLUME_STORAGE_POOL_MAP.REMOVED, null)).thenAnswer(i ->
                f.rows.get(VolumeStoragePoolMap.class).values().stream().map(VolumeStoragePoolMap.class::cast)
                        .filter(m -> m.getRemoved() == null && Long.valueOf(6L).equals(m.getVolumeId()) && Long.valueOf(9L).equals(m.getStoragePoolId())).toList());
        return new ImageEnsure(f, image, cache, pool);
    }

    @Test public void sharedImageEnsureUsesOnlyVerifiedOwnedVolumeAndAllocatedCache() {
        ImageEnsure f = imageEnsure();
        InstanceRecord foreign = new InstanceRecord(); foreign.setId(99L); foreign.setAccountId(6L); foreign.setImageId(7L);
        when(resolver.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null)).thenReturn(List.of(f.lifecycle.instance, foreign));
        EngineContext engine = EngineContext.getEngineContext();
        for (Object root : List.of(f.lifecycle.instance, f.lifecycle.service, f.lifecycle.stack)) {
            Map<String, Object> binding = new HashMap<>(ENSURE_BINDING);
            binding.put("targetType", root instanceof Instance ? "container" : root instanceof Service ? "service" : "stack");
            binding.put("targetId", String.valueOf(io.cattle.platform.object.util.ObjectUtils.getPropertyIgnoreErrors(root, "id")));
            var frame = engine.pushVerifiedExecution("volume", "6", binding);
            try {
                for (Object child : List.of(f.image, f.cache)) {
                    var dependency = resolver.resolveImageEnsureDependency(root, child, owner, frame, binding);
                    assertEquals(ApiKeyPolicyEvaluator.Target.stackResource("volume", "1v6", "1a5", "1st8"), dependency.scope());
                    assertTrue(dependency.resources().contains(ApiKeyPolicyEvaluator.Target.platformResource("image", "1img7")));
                    assertTrue(dependency.resources().stream().anyMatch(t -> "storagePool".equals(t.resourceType())));
                    assertFalse(dependency.resources().stream().anyMatch(t -> "1i99".equals(t.resourceId())));
                }
            } finally { engine.popVerifiedExecution(frame); }
        }
        Policy visible = mock(Policy.class); when(visible.authorizeObject(any())).thenAnswer(i -> i.getArgument(0));
        assertEquals(ApiKeyPolicyEvaluator.TargetLevel.UNRESOLVED, resolver.resolveObject("image", f.image, visible).level());
        assertEquals(ApiKeyPolicyEvaluator.TargetLevel.UNRESOLVED, resolver.resolveObject("imageStoragePoolMap", f.cache, owner).level());
        verify(resolver.objectManager, never()).find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null);
    }

    @Test public void imageEnsureRejectsAbsentWrongOrDifferentRootFrame() {
        ImageEnsure f = imageEnsure(); EngineContext engine = EngineContext.getEngineContext();
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.image, owner, null, ENSURE_BINDING));
        for (String type : List.of("service", "image", "Volume")) {
            var frame = engine.pushVerifiedExecution(type, "6", ENSURE_BINDING);
            try { assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.image, owner, frame, ENSURE_BINDING)); }
            finally { engine.popVerifiedExecution(frame); }
        }
        for (String id : List.of("", "99", "1s6", "999999999999999999999")) {
            var frame = engine.pushVerifiedExecution("volume", id, ENSURE_BINDING);
            try { assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.image, owner, frame, ENSURE_BINDING)); }
            finally { engine.popVerifiedExecution(frame); }
        }
        var empty = engine.pushVerifiedExecution("volume", "6", Map.of());
        try { assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.image, owner, empty, Map.of())); }
        finally { engine.popVerifiedExecution(empty); }
        for (Map<String, Object> mismatchedRoot : List.of(
                Map.<String, Object>of("keyId", "1c1", "requestId", "request1", "targetType", "service", "targetId", "99"),
                Map.<String, Object>of("keyId", "1c1", "requestId", "request1", "targetType", "container", "targetId", "3"))) {
            var wrongRoot = engine.pushVerifiedExecution("volume", "6", mismatchedRoot);
            try { assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.image, owner, wrongRoot, mismatchedRoot)); }
            finally { engine.popVerifiedExecution(wrongRoot); }
        }
        var frame = engine.pushVerifiedExecution("volume", "6", ENSURE_BINDING);
        try {
            assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, new Object(), owner, frame, ENSURE_BINDING));
            for (String field : List.of("keyId", "requestId", "targetType", "targetId")) {
                Map<String, Object> altered = new HashMap<>(ENSURE_BINDING); altered.put(field, "other");
                assertThrows(field, ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.image, owner, frame, altered));
            }
            ServiceRecord other = new ServiceRecord(); other.setId(99L); other.setAccountId(5L); other.setStackId(8L); f.lifecycle.add(Service.class, other);
            assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(other, f.image, owner, frame, ENSURE_BINDING));
        } finally { engine.popVerifiedExecution(frame); }
    }

    @Test public void sharedCacheOrdinaryOwnerDenialDoesNotBlockItsExactOwnedVolumeEnsure() {
        ImageEnsure f = imageEnsure(); Policy actual = owner;
        Policy internal = mock(Policy.class);
        when(internal.authorizeObject(any())).thenAnswer(i -> i.getArgument(0) instanceof ImageStoragePoolMap
                ? null : actual.authorizeObject(i.getArgument(0)));
        owner = internal;
        assertNull(owner.authorizeObject(f.cache));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("imageStoragePoolMap", f.cache, owner));
        EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("volume", "6", ENSURE_BINDING);
        try {
            var dependency = resolver.resolveImageEnsureDependency(f.lifecycle.service, f.cache, owner, frame, ENSURE_BINDING);
            assertEquals(ApiKeyPolicyEvaluator.Target.stackResource("volume", "1v6", "1a5", "1st8"), dependency.scope());
            assertTrue(dependency.resources().stream().anyMatch(t -> "imageStoragePoolMap".equals(t.resourceType())));
            assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.cache, owner, null, ENSURE_BINDING));
            Map<String, Object> wrongBinding = new HashMap<>(ENSURE_BINDING); wrongBinding.put("requestId", "other");
            assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.cache, owner, frame, wrongBinding));
            ImageStoragePoolMapRecord forged = new ImageStoragePoolMapRecord(); forged.from(f.cache); forged.setImageId(99L);
            assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, forged, owner, frame, ENSURE_BINDING));
            f.cache.setStoragePoolId(99L);
            assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.cache, owner, frame, ENSURE_BINDING));
            f.cache.setStoragePoolId(9L); f.cache.setRemoved(new java.util.Date(1));
            assertThrows(ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.cache, owner, frame, ENSURE_BINDING));
        } finally { engine.popVerifiedExecution(frame); }
    }

    @Test public void imageEnsureRejectsMissingForeignRemovedOrContradictoryDependencies() {
        for (String invalid : List.of("missing-volume", "foreign-volume", "removed-volume", "wrong-volume-image", "missing-image", "foreign-image", "removed-image", "purged-image",
                "missing-allocation", "removed-allocation", "purged-allocation", "wrong-allocation-volume", "missing-pool", "foreign-pool", "removed-pool",
                "missing-cache", "removed-cache", "purged-cache", "wrong-cache-image", "wrong-cache-pool", "fabricated-cache-image", "fabricated-cache-pool", "fabricated-cache-state", "fabricated-image-registry", "fabricated-image-state")) {
            ImageEnsure f = imageEnsure(); Object child = invalid.contains("cache") ? f.cache : f.image;
            switch (invalid) {
                case "missing-volume" -> f.lifecycle.rows.get(Volume.class).clear();
                case "foreign-volume" -> f.lifecycle.volume.setAccountId(6L);
                case "removed-volume" -> f.lifecycle.volume.setRemoved(new java.util.Date(1));
                case "wrong-volume-image" -> f.lifecycle.volume.setImageId(99L);
                case "missing-image" -> f.lifecycle.rows.get(Image.class).clear();
                case "foreign-image" -> f.image.setAccountId(6L);
                case "removed-image" -> f.image.setRemoved(new java.util.Date(1));
                case "purged-image" -> f.image.setState("purged");
                case "missing-allocation" -> f.lifecycle.rows.get(VolumeStoragePoolMap.class).clear();
                case "removed-allocation" -> f.lifecycle.poolMap.setRemoved(new java.util.Date(1));
                case "purged-allocation" -> f.lifecycle.poolMap.setState("purged");
                case "wrong-allocation-volume" -> f.lifecycle.poolMap.setVolumeId(99L);
                case "missing-pool" -> f.lifecycle.rows.get(StoragePool.class).clear();
                case "foreign-pool" -> f.pool.setAccountId(6L);
                case "removed-pool" -> f.pool.setRemoved(new java.util.Date(1));
                case "missing-cache" -> f.lifecycle.rows.get(ImageStoragePoolMap.class).clear();
                case "removed-cache" -> f.cache.setRemoved(new java.util.Date(1));
                case "purged-cache" -> f.cache.setState("purged");
                case "wrong-cache-image" -> f.cache.setImageId(99L);
                case "wrong-cache-pool" -> f.cache.setStoragePoolId(99L);
                case "fabricated-cache-image", "fabricated-cache-pool", "fabricated-cache-state" -> {
                    ImageStoragePoolMapRecord clone = new ImageStoragePoolMapRecord(); clone.from(f.cache);
                    if (invalid.endsWith("image")) clone.setImageId(99L);
                    else if (invalid.endsWith("pool")) clone.setStoragePoolId(99L); else clone.setState("active"); child = clone;
                }
                case "fabricated-image-registry" -> { ImageRecord clone = new ImageRecord(); clone.from(f.image); clone.setRegistryCredentialId(99L); child = clone; }
                case "fabricated-image-state" -> { ImageRecord clone = new ImageRecord(); clone.from(f.image); clone.setState("inactive"); child = clone; }
            }
            Object attempted = child; EngineContext engine = EngineContext.getEngineContext();
            var frame = engine.pushVerifiedExecution("volume", "6", ENSURE_BINDING);
            try { assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, attempted, owner, frame, ENSURE_BINDING)); }
            finally { engine.popVerifiedExecution(frame); }
        }
    }

    private CredentialRecord imageRegistry(ImageEnsure f) {
        CredentialRecord credential = new CredentialRecord(); credential.setId(20L); credential.setAccountId(5L);
        credential.setState("active"); credential.setKind("registryCredential"); credential.setRegistryId(21L); f.lifecycle.add(Credential.class, credential);
        StoragePoolRecord registry = new StoragePoolRecord(); registry.setId(21L); registry.setAccountId(5L); registry.setState("active"); registry.setKind("registry");
        f.lifecycle.add(StoragePool.class, registry); f.image.setRegistryCredentialId(20L); f.lifecycle.instance.setRegistryCredentialId(20L);
        return credential;
    }

    @Test public void privateRegistryEnsureRequiresActualOwnedActiveCredentialAndRegistry() {
        for (String invalid : List.of("valid", "missing-credential", "foreign-credential", "inactive-credential", "wrong-credential-kind", "removed-credential",
                "missing-registry", "foreign-registry", "inactive-registry", "wrong-registry-kind", "removed-registry", "different-instance-credential")) {
            ImageEnsure f = imageEnsure(); CredentialRecord credential = imageRegistry(f);
            StoragePoolRecord registry = (StoragePoolRecord) f.lifecycle.rows.get(StoragePool.class).get(21L);
            switch (invalid) {
                case "missing-credential" -> f.lifecycle.rows.get(Credential.class).clear();
                case "foreign-credential" -> credential.setAccountId(6L);
                case "inactive-credential" -> credential.setState("inactive");
                case "wrong-credential-kind" -> credential.setKind("apiKey");
                case "removed-credential" -> credential.setRemoved(new java.util.Date(1));
                case "missing-registry" -> f.lifecycle.rows.get(StoragePool.class).remove(21L);
                case "foreign-registry" -> registry.setAccountId(6L);
                case "inactive-registry" -> registry.setState("inactive");
                case "wrong-registry-kind" -> registry.setKind("docker");
                case "removed-registry" -> registry.setRemoved(new java.util.Date(1));
                case "different-instance-credential" -> f.lifecycle.instance.setRegistryCredentialId(99L);
            }
            EngineContext engine = EngineContext.getEngineContext(); var frame = engine.pushVerifiedExecution("volume", "6", ENSURE_BINDING);
            try {
                if (invalid.equals("valid")) assertNotNull(resolver.resolveImageEnsureDependency(f.lifecycle.service, f.cache, owner, frame, ENSURE_BINDING));
                else assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveImageEnsureDependency(f.lifecycle.service, f.cache, owner, frame, ENSURE_BINDING));
            } finally { engine.popVerifiedExecution(frame); }
        }
    }

    @Test public void imageEnsureRetainsEveryActualDependencyExplicitDenial() {
        ImageEnsure f = imageEnsure(); imageRegistry(f); EngineContext engine = EngineContext.getEngineContext();
        var frame = engine.pushVerifiedExecution("volume", "6", ENSURE_BINDING);
        try {
            var dependency = resolver.resolveImageEnsureDependency(f.lifecycle.service, f.cache, owner, frame, ENSURE_BINDING);
            for (var target : dependency.resources()) {
                ApiKeyPolicy policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, List.of(
                        new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.stack("1st8"), java.util.Set.of("upgrade")),
                        new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY, ApiKeyPolicy.Scope.resource(target.resourceType(), target.resourceId()), java.util.Set.of("upgrade"))));
                assertEquals(target.toString(), ApiKeyPolicyEvaluator.Reason.POLICY_DENIED, new ApiKeyPolicyEvaluator().evaluateAuthorizedDependency(policy,
                        new ApiKeyPolicyEvaluator.Request(true, "upgrade", true, dependency.resources()), java.time.Instant.EPOCH).reason());
            }
        } finally { engine.popVerifiedExecution(frame); }
    }

    @Test public void imageDependencyUsesPersistedInstanceNotImageVisibility() {
        Instance instance = imageInstance();
        Image image = image();
        when(owner.authorizeObject(image)).thenReturn(null);
        var dependency = resolver.resolveImageDependency(instance, image, owner);
        var target = dependency.scope();
        assertEquals("image", target.resourceType()); assertEquals("1st8", target.stackId());
        assertEquals("1a5", target.projectId());
        assertEquals(List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")), dependency.instances());
        assertEquals(ApiKeyPolicyEvaluator.TargetLevel.UNRESOLVED, resolver.resolveObject("image", image(), owner).level());
    }
    @Test public void serviceAndStackCanAuthorizeTheirActualImageDependency() {
        Instance instance = imageInstance(); when(instance.getServiceId()).thenReturn(2L);
        Service service = mock(Service.class); when(service.getId()).thenReturn(2L);
        when(service.getAccountId()).thenReturn(5L); when(service.getStackId()).thenReturn(8L);
        when(resolver.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        for (Object root : List.of(service, stack)) {
            var dependency = resolver.resolveImageDependency(root, image(), owner);
            assertEquals("1st8", dependency.scope().stackId());
            assertEquals(List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")), dependency.instances());
        }
    }
    @Test public void realServiceMapSuppliesMissingDenormalizedParents() {
        Instance instance = imageInstance(); when(instance.getStackId()).thenReturn(null); when(instance.getServiceId()).thenReturn(null);
        Service service = mapService(instance, 2L, 5L, 8L);
        assertEquals("1st8", resolver.resolveObject("container", instance, owner).stackId());
        for (Object root : List.of(service, stack)) {
            var dependency = resolver.resolveImageDependency(root, image(), owner);
            assertEquals("1st8", dependency.scope().stackId());
            assertEquals(List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")), dependency.instances());
        }
        when(service.getAccountId()).thenReturn(6L);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageDependency(service, image(), owner));
    }
    @Test public void persistedUpgradeMapPreservesParentsForOldChildAndImageDependency() {
        Instance instance = imageInstance(); when(instance.getStackId()).thenReturn(null);
        Service service = mapService(instance, 2L, 5L, 8L);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(),
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of());
        upgradeMap(instance, service);
        var target = resolver.resolveObject("container", instance, owner);
        assertEquals("1st8", target.stackId()); assertEquals("1a5", target.projectId()); assertEquals("1i3", target.resourceId());
        var dependency = resolver.resolveImageDependency(service, image(), owner);
        assertEquals("1st8", dependency.scope().stackId());
        assertEquals(List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")), dependency.instances());
    }
    @Test public void upgradeMapCannotBeOrdinaryUnmanagedForeignRemovedOrContradictory() {
        for (String invalid : List.of("not-upgrade", "null-upgrade", "null-managed", "removed", "foreign-account", "foreign-instance", "missing-service", "removed-service", "owner-denied", "different-stack")) {
            Instance instance = instance();
            Service service = mapService(instance, 2L, 5L, 8L);
            when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(),
                    SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of());
            ServiceExposeMap map = upgradeMap(instance, service);
            switch (invalid) {
                case "not-upgrade" -> when(map.getUpgrade()).thenReturn(false);
                case "null-upgrade" -> when(map.getUpgrade()).thenReturn(null);
                case "null-managed" -> when(map.getManaged()).thenReturn(null);
                case "removed" -> when(map.getRemoved()).thenReturn(new java.util.Date());
                case "foreign-account" -> when(map.getAccountId()).thenReturn(6L);
                case "foreign-instance" -> when(map.getInstanceId()).thenReturn(99L);
                case "missing-service" -> when(map.getServiceId()).thenReturn(null);
                case "removed-service" -> when(service.getRemoved()).thenReturn(new java.util.Date());
                case "owner-denied" -> when(owner.authorizeObject(service)).thenReturn(null);
                case "different-stack" -> when(instance.getStackId()).thenReturn(9L);
            }
            if (List.of("not-upgrade", "null-upgrade", "null-managed").contains(invalid)) {
                // The constrained persisted query excludes these ordinary maps;
                // resolving the container may retain only its Project, never its Service Stack.
                when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(),
                        SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true))
                        .thenReturn(List.of());
                assertNull(invalid, resolver.resolveObject("container", instance, owner).stackId());
            } else {
                assertThrows(invalid, ClientVisibleException.class, () -> resolver.resolveObject("container", instance, owner));
            }
        }
    }
    @Test public void ordinaryUnmanagedMapIsNotQueriedAsAnOwnedParent() {
        Instance instance = instance();
        Service service = mapService(instance, 2L, 5L, 8L);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(),
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of());
        ServiceExposeMap ordinary = mock(ServiceExposeMap.class); when(ordinary.getManaged()).thenReturn(false); when(ordinary.getUpgrade()).thenReturn(false);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(),
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false)).thenReturn(List.of(ordinary));
        assertNull(resolver.resolveObject("container", instance, owner).stackId());
        verify(resolver.objectManager, never()).find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(),
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false);
        verify(resolver.objectManager, never()).loadResource(Service.class, 2L);
    }
    private ServiceExposeMap upgradeMap(Instance instance, Service service) {
        Long instanceId = instance.getId(), accountId = instance.getAccountId(), serviceId = service.getId();
        ServiceExposeMap map = mock(ServiceExposeMap.class);
        when(map.getInstanceId()).thenReturn(instanceId); when(map.getAccountId()).thenReturn(accountId);
        when(map.getServiceId()).thenReturn(serviceId); when(map.getManaged()).thenReturn(false); when(map.getUpgrade()).thenReturn(true);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, instanceId,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, false, SERVICE_EXPOSE_MAP.UPGRADE, true)).thenReturn(List.of(map));
        return map;
    }
    @Test public void managedMapCannotContradictExplicitStackOrItsOwnAccount() {
        Instance instance = imageInstance(); Service service = mapService(instance, 2L, 5L, 9L);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("container", instance, owner));
        when(service.getStackId()).thenReturn(8L);
        var maps = resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true);
        when(maps.getFirst().getAccountId()).thenReturn(6L);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageDependency(service, image(), owner));
    }
    @Test public void nullAndNonNullManagedServiceStacksRemainAmbiguousInEitherOrder() {
        Instance instance = imageInstance(); when(instance.getStackId()).thenReturn(null);
        Service first = mapService(instance, 2L, 5L, 8L); when(first.getStackId()).thenReturn(null);
        var firstMaps = resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true);
        mapService(instance, 4L, 5L, 8L);
        var secondMaps = resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true))
                .thenReturn(List.of(firstMaps.getFirst(), secondMaps.getFirst()));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("container", instance, owner));
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true))
                .thenReturn(List.of(secondMaps.getFirst(), firstMaps.getFirst()));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("container", instance, owner));
    }
    private Service mapService(Instance instance, long id, long account, long stackId) {
        Long instanceId = instance.getId();
        Service service = mock(Service.class); when(service.getId()).thenReturn(id);
        when(service.getAccountId()).thenReturn(account); when(service.getStackId()).thenReturn(stackId);
        when(resolver.objectManager.loadResource(Service.class, id)).thenReturn(service);
        ServiceExposeMap map = mock(ServiceExposeMap.class); when(map.getInstanceId()).thenReturn(instanceId);
        when(map.getServiceId()).thenReturn(id); when(map.getAccountId()).thenReturn(account); when(map.getManaged()).thenReturn(true);
        when(resolver.objectManager.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, instanceId,
                SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of(map));
        return service;
    }
    @Test public void imageWithoutPersistedLinkOrUnrelatedRootCannotBorrowScope() {
        Instance instance = imageInstance();
        Instance other = mock(Instance.class); when(other.getId()).thenReturn(99L);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageDependency(other, image(), owner));
        when(resolver.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null)).thenReturn(List.of());
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageDependency(instance, image(), owner));
    }
    @Test public void revokedOwnerOrForeignAdditionalImageReferenceIsDenied() {
        Instance instance = imageInstance(); when(owner.authorizeObject(instance)).thenReturn(null);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.resolveImageDependency(instance, image(), owner)).getCode());
        when(owner.authorizeObject(instance)).thenReturn(instance);
        Instance foreign = mock(Instance.class); when(foreign.getImageId()).thenReturn(7L);
        when(owner.authorizeObject(foreign)).thenReturn(null);
        when(resolver.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null)).thenReturn(List.of(instance, foreign));
        assertThrows(ClientVisibleException.class, () -> resolver.resolveImageDependency(instance, image(), owner));
    }
    @Test public void realAccountPolicyRequiresLiveOwnedInstanceWithoutHttpContext() {
        Account project = mock(Account.class), principal = mock(Account.class);
        when(project.getId()).thenReturn(5L); when(principal.getId()).thenReturn(10L);
        Policy real = new AccountPolicy(project, principal, java.util.Set.of(), new NoPolicyOptions());
        Instance instance = imageInstance();
        io.github.ibuildthecloud.gdapi.context.ApiContext.remove();
        var dependency = resolver.resolveImageDependency(instance, image(), real);
        assertEquals("1st8", dependency.scope().stackId());
        assertEquals(List.of(ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8")), dependency.instances());
        when(instance.getAccountId()).thenReturn(6L);
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.resolveImageDependency(instance, image(), real)).getCode());
        assertNull(io.github.ibuildthecloud.gdapi.context.ApiContext.getContext());
    }
    @Test public void everyActualContainerIdIsPreservedForResourceDenyInsteadOfBecomingImageId() {
        Instance first = imageInstance();
        Instance second = instance(); when(second.getId()).thenReturn(4L);
        when(second.getImageId()).thenReturn(7L); when(second.getStackId()).thenReturn(8L);
        when(resolver.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null))
                .thenReturn(List.of(first, second));
        var dependency = resolver.resolveImageDependency(stack, image(), owner);
        assertEquals(ApiKeyPolicyEvaluator.Target.stackResource("image", "1img7", "1a5", "1st8"), dependency.scope());
        assertEquals(List.of(
                ApiKeyPolicyEvaluator.Target.stackResource("container", "1i3", "1a5", "1st8"),
                ApiKeyPolicyEvaluator.Target.stackResource("container", "1i4", "1a5", "1st8")), dependency.instances());
        var deniedContainer = new ApiKeyPolicy.Rule("child-deny", ApiKeyPolicy.Effect.DENY,
                ApiKeyPolicy.Scope.resource("container", "1i4"), java.util.Set.of("upgrade"));
        var policy = new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.ALLOW, null, List.of(deniedContainer));
        var evaluator = new ApiKeyPolicyEvaluator();
        java.time.Instant now = java.time.Instant.parse("2026-10-08T00:00:00Z");
        assertTrue(evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, "upgrade", true,
                List.of(dependency.scope())), now).allowed());
        var result = evaluator.evaluate(policy, new ApiKeyPolicyEvaluator.Request(true, "upgrade", true,
                dependency.instances()), now);
        assertFalse(result.allowed());
        assertEquals(List.of("child-deny"), result.matchedRuleIds());
    }
    @Test public void standaloneProjectDependencyPreservesRealContainerAndImmutableTargetList() {
        Instance instance = imageInstance(); when(instance.getStackId()).thenReturn(null);
        var dependency = resolver.resolveImageDependency(instance, image(), owner);
        assertEquals(ApiKeyPolicyEvaluator.Target.projectResource("image", "1img7", "1a5"), dependency.scope());
        var target = ApiKeyPolicyEvaluator.Target.projectResource("container", "1i3", "1a5");
        assertEquals(List.of(target), dependency.instances());
        assertThrows(UnsupportedOperationException.class, () -> dependency.instances().clear());
        var mutable = new java.util.ArrayList<>(List.of(target));
        var copy = new ApiKeyTargetResolver.ImageDependency(dependency.scope(), mutable);
        mutable.clear();
        assertEquals(List.of(target), copy.instances());
    }
    private Image image() { Image image = mock(Image.class); when(image.getId()).thenReturn(7L); return image; }
    private Instance imageInstance() {
        Instance instance = instance(); when(instance.getStackId()).thenReturn(8L); when(instance.getImageId()).thenReturn(7L);
        when(resolver.objectManager.find(Instance.class, INSTANCE.IMAGE_ID, 7L, INSTANCE.REMOVED, null)).thenReturn(List.of(instance));
        return instance;
    }
    @Before public void setup() {
        resolver = new ApiKeyTargetResolver();
        resolver.objectManager = mock(ObjectManager.class);
        resolver.idFormatter = mock(IdFormatter.class);
        when(resolver.idFormatter.parseId(anyString())).thenAnswer(i -> i.<String>getArgument(0).replaceFirst("^1[a-z]+", ""));
        when(resolver.idFormatter.formatId(anyString(), any())).thenAnswer(i -> {
            String prefix = Map.of("project", "a", "stack", "st", "service", "s", "container", "i", "volume", "v", "image", "img").getOrDefault(i.<String>getArgument(0), "internal");
            return "1" + prefix + i.getArgument(1);
        });
        owner = mock(Policy.class);
        when(owner.authorizeObject(any())).thenAnswer(i -> i.getArgument(0));
        when(owner.getAccountId()).thenReturn(5L);
        Account project = mock(Account.class);
        when(project.getKind()).thenReturn("project"); when(project.getId()).thenReturn(5L);
        when(resolver.objectManager.loadResource(Account.class, 5L)).thenReturn(project);
        stack = mock(Stack.class);
        when(stack.getId()).thenReturn(8L); when(stack.getAccountId()).thenReturn(5L);
        when(resolver.objectManager.loadResource(Stack.class, 8L)).thenReturn(stack);
    }
    private Instance instance() {
        Instance instance = mock(Instance.class);
        when(instance.getId()).thenReturn(3L); when(instance.getAccountId()).thenReturn(5L);
        when(instance.getServiceId()).thenReturn(null); when(instance.getStackId()).thenReturn(null);
        return instance;
    }
    private Field inputField(boolean create, boolean update, String type) {
        Field field = mock(Field.class);
        when(field.isCreate()).thenReturn(create); when(field.isUpdate()).thenReturn(update);
        when(field.getType()).thenReturn(type);
        return field;
    }
    private ApiRequest inputRequest(String type, String method, Map<String, Field> fields, Map<String, Object> body) {
        ApiRequest request = new ApiRequest(null, null);
        request.setType(type); request.setMethod(method); request.setRequestObject(body);
        SchemaFactory factory = mock(SchemaFactory.class); Schema schema = mock(Schema.class);
        when(schema.getResourceFields()).thenReturn(fields); when(factory.getSchema(type)).thenReturn(schema);
        request.setSchemaFactory(factory); return request;
    }
    @Test public void readOnlyServiceParentCannotGrantStackCreateAuthority() {
        Service service = mock(Service.class);
        when(service.getId()).thenReturn(2L); when(service.getAccountId()).thenReturn(5L); when(service.getStackId()).thenReturn(8L);
        when(resolver.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        ApiRequest request = inputRequest("container", "POST",
                Map.of("serviceId", inputField(false, false, "reference[service]")), Map.of("serviceId", "1s2"));
        var target = resolver.resolve(request, owner).getFirst();
        assertEquals(ApiKeyPolicyEvaluator.TargetLevel.PROJECT, target.level());
        assertNull(target.stackId()); assertEquals("1a5", target.projectId());
        verify(resolver.objectManager, never()).loadResource(Service.class, 2L);
    }
    @Test public void standaloneContainerUsesPersistedStackAndCanonicalType() {
        Instance instance = instance(); when(instance.getStackId()).thenReturn(8L);
        var target = resolver.resolveObject("instance", instance, owner);
        assertEquals("container", target.resourceType()); assertEquals("1i3", target.resourceId());
        assertEquals("1st8", target.stackId()); assertEquals("1a5", target.projectId());
    }
    @Test public void serviceFallbackHasAuthoritativeAccountAndStack() {
        Instance instance = instance(); when(instance.getServiceId()).thenReturn(2L);
        Service service = mock(Service.class);
        when(service.getStackId()).thenReturn(8L); when(service.getAccountId()).thenReturn(5L);
        when(resolver.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        assertEquals("1st8", resolver.resolveObject("instance", instance, owner).stackId());
        when(service.getAccountId()).thenReturn(6L);
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> resolver.resolveObject("instance", instance, owner)).getCode());
    }
    @Test public void contradictoryServiceAndContainerStackCannotPass() {
        Instance instance = instance(); when(instance.getStackId()).thenReturn(8L); when(instance.getServiceId()).thenReturn(2L);
        Service service = mock(Service.class); when(service.getAccountId()).thenReturn(5L); when(service.getStackId()).thenReturn(9L);
        when(resolver.objectManager.loadResource(Service.class, 2L)).thenReturn(service);
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("container", instance, owner));
    }
    @Test public void volumeUsesItsOwnPersistedStack() {
        Volume volume = mock(Volume.class); when(volume.getId()).thenReturn(4L);
        when(volume.getAccountId()).thenReturn(5L); when(volume.getStackId()).thenReturn(8L);
        assertEquals("1st8", resolver.resolveObject("volume", volume, owner).stackId());
    }
    @Test public void typedParentCannotUseUnrelatedPrefix() {
        assertEquals(Long.valueOf(8), resolver.parseScopeId("stack", "1st8"));
        assertThrows(ClientVisibleException.class, () -> resolver.parseScopeId("stack", "1s8"));
        assertThrows(ClientVisibleException.class, () -> resolver.parseScopeId("stack", "8"));
    }
    @Test public void createPayloadLoadsParentInsteadOfTrustingItsAccount() {
        ApiRequest request = inputRequest("service", "POST", Map.of(
                "stackId", inputField(true, false, "reference[stack]"),
                "accountId", inputField(true, false, "reference[account]")),
                Map.of("stackId", "1st8", "accountId", "1a6"));
        assertThrows(ClientVisibleException.class, () -> resolver.resolve(request, owner));
        request.setRequestObject(Map.of("stackId", "1st8", "accountId", "1a5"));
        assertEquals("1st8", resolver.resolve(request, owner).getFirst().stackId());
    }
    @Test public void writableDirectStackParentIsStillAValidStandaloneCreate() {
        ApiRequest request = inputRequest("container", "POST",
                Map.of("stackId", inputField(true, false, "reference[stack]")), Map.of("stackId", "1st8"));
        assertEquals("1st8", resolver.resolve(request, owner).getFirst().stackId());
    }
    @Test public void unknownInstanceParentCannotGrantStackCreateAuthority() {
        ApiRequest request = inputRequest("container", "POST", Map.of(), Map.of("instanceId", "1i3"));
        assertNull(resolver.resolve(request, owner).getFirst().stackId());
        verify(resolver.objectManager, never()).loadResource(Instance.class, 3L);
    }
    @Test public void stringWithParentLikeNameIsNotAResourceReference() {
        ApiRequest request = inputRequest("container", "POST",
                Map.of("stackId", inputField(true, true, "string")), Map.of("stackId", "1st8"));
        assertNull(resolver.resolve(request, owner).getFirst().stackId());
        verify(resolver.objectManager, never()).loadResource(Stack.class, 8L);
    }
    @Test public void immutableUpdateParentDoesNotReplacePersistedAncestry() {
        Instance instance = instance(); when(instance.getStackId()).thenReturn(8L);
        when(resolver.objectManager.loadResource("container", 3L)).thenReturn(instance);
        ApiRequest request = inputRequest("container", "PUT",
                Map.of("stackId", inputField(true, false, "reference[stack]")), Map.of("stackId", "1st9"));
        request.setId("1i3");
        var targets = resolver.resolve(request, owner);
        assertEquals(1, targets.size()); assertEquals("1st8", targets.getFirst().stackId());
        verify(resolver.objectManager, never()).loadResource(Stack.class, 9L);
    }
    @Test public void ignoredReadOnlyReferencesDoNotRequireAdditionalAuthority() {
        ApiRequest request = inputRequest("container", "POST",
                Map.of("serviceId", inputField(false, false, "reference[service]")), Map.of("serviceId", "1s2"));
        assertTrue(resolver.references(request, owner).isEmpty());
        verifyNoInteractions(resolver.objectManager);
        request.setMethod("GET");
        assertTrue(resolver.references(request, owner).isEmpty());
    }
    @Test public void collectionActionUsesItsActualInputSchema() {
        ApiRequest request = inputRequest("service", "POST", Map.of(), Map.of("stackId", "1st8"));
        Schema schema = request.getSchemaFactory().getSchema("service");
        Action action = mock(Action.class); when(action.getInput()).thenReturn("actionInput");
        when(schema.getCollectionActions()).thenReturn(Map.of("update", action));
        Schema input = mock(Schema.class);
        Field stackField = inputField(true, false, "reference[stack]");
        when(input.getResourceFields()).thenReturn(Map.of("stackId", stackField));
        when(request.getSchemaFactory().getSchema("actionInput")).thenReturn(input);
        when(resolver.objectManager.loadResource("stack", "8")).thenReturn(stack);
        request.setAction("update");
        assertEquals("1st8", resolver.references(request, owner).getFirst().stackId());
    }
    @Test public void nestedUpdateInputUsesCreateFieldsAsTheValidatorDoes() {
        ApiRequest request = inputRequest("service", "PUT",
                Map.of("launchConfig", inputField(false, true, "launchConfig")),
                Map.of("launchConfig", Map.of("stackId", "1st8")));
        Schema nested = mock(Schema.class);
        Field stackField = inputField(true, false, "reference[stack]");
        when(nested.getResourceFields()).thenReturn(Map.of("stackId", stackField));
        when(request.getSchemaFactory().getSchema("launchConfig")).thenReturn(nested);
        when(resolver.objectManager.loadResource("stack", "8")).thenReturn(stack);
        assertEquals("1st8", resolver.references(request, owner).getFirst().stackId());
    }
    @Test public void putUsesResourceFieldsEvenWithAnActionQueryParameter() {
        ApiRequest request = inputRequest("container", "PUT",
                Map.of("stackId", inputField(true, false, "reference[stack]")), Map.of("stackId", "1st9"));
        request.setAction("update");
        assertTrue(resolver.references(request, owner).isEmpty());
        assertNull(resolver.resolve(request, owner).stream().findFirst().orElse(null));
        verifyNoInteractions(resolver.objectManager);
    }
    @Test public void namedSettingsAreNotParsedAsNumericIds() {
        ApiRequest request = new ApiRequest(null, null); request.setType("setting"); request.setMethod("GET");
        request.setId("api.host");
        var target = resolver.resolve(request, owner).getFirst();
        assertEquals("api.host", target.resourceId()); assertEquals(ApiKeyPolicyEvaluator.TargetLevel.PLATFORM, target.level());
        verifyNoInteractions(resolver.objectManager);
    }
    @Test public void syntheticIdentityUsesExplicitModelContractNotUnknownTypeGuess() {
        NamedResourceIdentity setting = mock(NamedResourceIdentity.class);
        when(setting.getApiResourceType()).thenReturn("setting"); when(setting.getApiResourceId()).thenReturn("api.host");
        var target = resolver.resolveObject("activeSetting", setting, owner);
        assertEquals("setting", target.resourceType()); assertEquals("api.host", target.resourceId());
        verifyNoInteractions(resolver.idFormatter);
        when(setting.getApiResourceType()).thenReturn("futurePlugin");
        assertThrows(ClientVisibleException.class, () -> resolver.resolveObject("unknown", setting, owner));
    }
}
