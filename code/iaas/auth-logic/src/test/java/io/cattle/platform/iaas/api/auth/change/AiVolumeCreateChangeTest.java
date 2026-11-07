package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.StorageDriver;
import io.cattle.platform.core.model.VolumeStoragePoolMap;
import io.cattle.platform.core.model.tables.records.VolumeRecord;
import io.cattle.platform.core.model.tables.records.StorageDriverRecord;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AiVolumeCreateChangeTest {
    private static final AiResourceChangeDescriptor CREATE = AiResourceChangeDescriptor.require("volume", "create");
    private Object previousBridge;
    @Before public void enableFixtureBridge() {
        previousBridge = io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
    }
    @After public void removeContext() {
        ApiContext.remove();
        if (previousBridge == null) io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previousBridge);
    }
    static VolumeRecord volume() {
        var volume = new VolumeRecord(); volume.setId(91L); volume.setUuid("new-volume"); volume.setAccountId(77L);
        volume.setKind("volume"); volume.setName("new-data"); volume.setState("inactive"); volume.setAllocationState("inactive");
        volume.setDeviceNumber(-1); volume.setExternalId("new-data"); volume.setUri("local:///new-data"); volume.setData(new LinkedHashMap<>());
        DataAccessor.setField(volume, "driver", "local"); DataAccessor.setField(volume, "driverOpts", Map.of()); return volume;
    }
    @Test public void exactNativeFactoryAcceptsOnlyNameDescriptionAndNeverWritableReadonlyFlags() {
        assertEquals("POST", CREATE.method()); assertNull(CREATE.action()); assertEquals("create", CREATE.keyOperation());
        assertFalse(CREATE.requiresStackParent());
        assertEquals(Map.of("type", "volume", "name", "new-data", "driver", "local", "driverOpts", Map.of()),
                CREATE.body(CREATE.parameters(Map.of("name", "new-data"))));
        assertFalse(CREATE.body(Map.of("name", "new-data")).containsKey("isHostPath"));
        for (String operation : List.of("update_metadata", "start", "stop", "remove", "allocate", "snapshot"))
            assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("volume", operation));
        assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("persistentVolume", "create"));
    }
    @Test public void rawIdsPathsOptionsCredentialsAndMalformedNamesRejectBeforeNativeCreate() {
        for (String field : List.of("driver", "driverOpts", "storageDriverId", "hostId", "accountId", "resourceId", "path", "uri", "sizeMb",
                "isNative", "isHostPath", "options", "credentials", "image")) {
            var input = new LinkedHashMap<String, Object>(Map.of("name", "new-data")); input.put(field, "PRIVATE");
            assertEquals("ResourceChangeInputInvalid", assertThrows(ClientVisibleException.class, () -> CREATE.parameters(input)).getCode());
        }
        for (String name : List.of("", "/host/path", "../data", "local:///data", "with space", "a".repeat(64)))
            assertThrows(ClientVisibleException.class, () -> CREATE.parameters(Map.of("name", name)));
    }
    @Test public void exactNewUuidAndLogicalDetachedReadbackDoNotAdoptSameNameOldOrAllocatedVolumes() {
        var parameters = Map.<String, Object>of("name", "new-data");
        assertTrue(AiVolumeCreateChange.complete(volume(), parameters, 91L, "new-volume", 77L));
        List<Consumer<VolumeRecord>> drifts = List.of(v -> v.setId(90L), v -> v.setUuid("old-same-name"), v -> v.setAccountId(88L),
                v -> v.setState("active"), v -> v.setAllocationState("active"), v -> v.setHostId(1L), v -> v.setImageId(1L),
                v -> v.setInstanceId(1L), v -> v.setStorageDriverId(1L), v -> v.setDeviceNumber(0), v -> v.setSizeMb(1L),
                v -> v.setUri("file:///host/path"), v -> DataAccessor.setField(v, "driverOpts", Map.of("device", "/host/path")),
                v -> DataAccessor.setField(v, "isNative", true), v -> DataAccessor.setField(v, "isHostPath", true));
        for (var drift : drifts) { var changed = volume(); drift.accept(changed);
            assertFalse(AiVolumeCreateChange.complete(changed, parameters, 91L, "new-volume", 77L)); }
    }
    @Test public void onlyCompletedNativeCreateStateCanVerifyTheNewUnallocatedRecord() {
        var parameters = Map.<String, Object>of("name", "new-data");
        assertTrue(AiVolumeCreateChange.complete(volume(), parameters, 91L, "new-volume", 77L));
        for (String state : List.of("requested", "registering", "creating", "activating", "active", "detached", "removing", "removed", "purged", "error")) {
            var changed = volume(); changed.setState(state);
            assertFalse(state, AiVolumeCreateChange.complete(changed, parameters, 91L, "new-volume", 77L));
        }
    }
    @Test public void humanCreateReviewUsesTheSameNativeInactiveTerminalState() {
        var f = fixture();
        var state = f.domain.changes(CREATE, null, Map.of("name", "new-data")).stream()
                .filter(row -> "state".equals(row.get("field"))).findFirst().orElseThrow();
        assertNull(state.get("before")); assertEquals("inactive", state.get("after"));
    }
    @Test public void freshNativePostSchemaAndReadonlyFieldsRemainTheAuthorityBoundary() throws Exception {
        var f = fixture(); var parameters = Map.<String, Object>of("name", "new-data");
        assertEquals(CREATE.body(parameters), f.domain.prepare(f.request, CREATE, null, parameters));
        when(f.schema.getCollectionMethods()).thenReturn(List.of("GET"));
        assertEquals(403, assertThrows(ClientVisibleException.class, () -> f.domain.prepare(f.request, CREATE, null, parameters)).getStatus());
        verify(f.validation, times(1)).generate(any());
    }
    @Test public void projectUuidDriftAndPluginShadowingCannotReuseAnApprovedLocalScope() {
        var f = fixture(); var scope = f.domain.volumeCreateScope(f.request);
        var project = f.domain.objects.loadResource(Account.class, 77L); project.setUuid("other-project");
        assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, CREATE, null, Map.of("name", "new-data"), Map.of("referenceSnapshot", scope))).getCode());
        project.setUuid("project-uuid"); var plugin = new StorageDriverRecord(); plugin.setName("local"); plugin.setAccountId(77L);
        when(f.domain.objects.find(StorageDriver.class, "accountId", 77L, "name", "local", "removed", null)).thenReturn(List.of(plugin));
        assertEquals("ResourceChangeUnsupported", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, CREATE, null, Map.of("name", "new-data"))).getCode());
    }
    @Test public void noMountOrStoragePoolMayBeMistakenForUnallocatedLogicalCompletion() {
        var f = fixture(); var volume = volume();
        var identity = Map.<String, Object>of("volumeCreateId", 91L, "volumeCreateUuid", "new-volume", "volumeCreateAccountId", 77L);
        assertTrue(f.domain.complete(CREATE, volume, Map.of("name", "new-data"), identity));
        when(f.domain.objects.find(Mount.class, "volumeId", 91L)).thenReturn(List.of(mock(Mount.class)));
        assertFalse(f.domain.complete(CREATE, volume, Map.of("name", "new-data"), identity));
        when(f.domain.objects.find(Mount.class, "volumeId", 91L)).thenReturn(List.of());
        when(f.domain.objects.find(VolumeStoragePoolMap.class, "volumeId", 91L)).thenReturn(List.of(mock(VolumeStoragePoolMap.class)));
        assertFalse(f.domain.complete(CREATE, volume, Map.of("name", "new-data"), identity));
    }
    @Test public void boundKeyRevocationPropagatesWithoutDroppingTheExistingKeyGuard() {
        var f = fixture();
        doThrow(new ClientVisibleException(403, "ApiKeyRevoked")).when(f.domain.keys).authorizeBoundDomain(anyMap(), any());
        assertEquals("ApiKeyRevoked", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, CREATE, null, Map.of("name", "new-data"), Map.of())).getCode());
    }
    @Test public void terminalReadUsesRealCanonicalCodecExactIdUuidAndFreshOwner() throws Exception {
        var f = fixture(); var volume = volume();
        var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(f.schemas);
        formatter.setTypeMappings(Map.of("volume", "v")); when(f.schemas.getBaseType("volume")).thenReturn("volume");
        ApiContext.getContext().setIdFormatter(formatter);
        var resolver = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver();
        var field = resolver.getClass().getDeclaredField("idFormatter"); field.setAccessible(true); field.set(resolver, formatter);
        f.domain.targets = resolver;
        when(f.schema.getResourceMethods()).thenReturn(List.of("GET"));
        when(f.policy.authorizeObject(volume)).thenReturn(volume);
        when(f.domain.objects.loadResource("volume", 91L)).thenReturn(volume);
        var manager = mock(io.github.ibuildthecloud.gdapi.request.resource.ResourceManager.class);
        f.domain.managers = mock(io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator.class);
        when(f.domain.managers.getResourceManagerByType("volume")).thenReturn(manager);
        when(manager.getById(eq("volume"), eq("1v91"), any())).thenReturn(volume);
        var plan = Map.<String, Object>of("internalResourceId", 91L, "resourceId", "1v91", "createdResourceUuid", "new-volume");
        assertSame(volume, f.domain.receiptTarget(f.request, CREATE, plan));
        volume.setUuid("old-same-name");
        assertThrows(ClientVisibleException.class, () -> f.domain.receiptTarget(f.request, CREATE, plan));
        volume.setUuid("new-volume"); volume.setAccountId(88L);
        assertThrows(ClientVisibleException.class, () -> f.domain.receiptTarget(f.request, CREATE, plan));
        volume.setAccountId(77L);
        var old = volume(); old.setId(90L); old.setUuid("old-same-name");
        when(manager.getById(eq("volume"), eq("1v91"), any())).thenReturn(old);
        assertThrows(ClientVisibleException.class, () -> f.domain.receiptTarget(f.request, CREATE, plan));
        assertThrows(ClientVisibleException.class, () -> resolver.parseScopeId("volume", "1s91"));
        assertThrows(ClientVisibleException.class, () -> resolver.parseScopeId("volume", "91"));
    }
    private static AiResourceChangeDomainTest.Fixture fixture() {
        var f = new AiResourceChangeDomainTest.Fixture();
        f.domain.objects.loadResource(Account.class, 77L).setUuid("project-uuid");
        when(f.schemas.getSchema("volume")).thenReturn(f.schema); when(f.schema.getCollectionMethods()).thenReturn(List.of("GET", "POST"));
        var driver = new io.github.ibuildthecloud.gdapi.model.impl.FieldImpl(); driver.setCreate(true);
        var options = new io.github.ibuildthecloud.gdapi.model.impl.FieldImpl(); options.setCreate(true);
        when(f.schema.getResourceFields()).thenReturn(Map.of("name", f.name, "driver", driver, "driverOpts", options));
        return f;
    }
}
