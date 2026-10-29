package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Mount;
import io.cattle.platform.core.model.Nic;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.MountRecord;
import io.cattle.platform.core.model.tables.records.NicRecord;
import io.cattle.platform.core.model.tables.records.PortRecord;
import io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.After;
import org.junit.Test;

public class AiResourceChangeDomainTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void externalServiceUsesTheExactFilteredNativeManagerForEveryTypedLifecycleIntent() throws Exception {
        Fixture f = new Fixture(); when(f.schemas.getSchema("externalService")).thenReturn(f.schema);
        Map<String, io.github.ibuildthecloud.gdapi.model.Field> fields = new java.util.LinkedHashMap<>();
        for (String field : List.of("name", "description", "hostname", "externalIpAddresses", "stackId", "launchConfig", "startOnCreate")) {
            FieldImpl capability = new FieldImpl(); capability.setCreate(true); capability.setUpdate(true); fields.put(field, capability);
        }
        when(f.schema.getResourceFields()).thenReturn(fields);
        ResourceManager filtered = mock(ResourceManager.class); f.domain.managers = mock(ResourceManagerLocator.class);
        when(f.domain.managers.getResourceManagerByType("externalService")).thenReturn(filtered);
        ServiceRecord target = new ServiceRecord(); target.setId(7L); target.setKind("externalService");
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            for (String operation : List.of("create", "update_targets", "update_metadata", "start", "stop", "remove")) {
                var descriptor = AiResourceChangeDescriptor.require("externalService", operation);
                Map<String, Object> parameters = descriptor.parameters(switch (operation) {
                case "create" -> Map.of("name", "db", "stackId", "1st3", "hostname", "db.test");
                case "update_targets" -> Map.of("externalIpAddresses", List.of("192.0.2.1"));
                case "update_metadata" -> Map.of("description", "");
                default -> Map.of();
                });
                java.util.function.Consumer<ApiRequest> exact = actual -> {
                    assertEquals("externalService", actual.getType()); assertEquals(descriptor.method(), actual.getMethod());
                    assertEquals(descriptor.action(), actual.getAction()); assertEquals(descriptor.body(parameters), actual.getRequestObject());
                    assertSame(actual, ApiContext.getContext().getApiRequest()); assertTrue(AiResourceChangeDomain.trustedDomain(actual));
                };
                when(filtered.create(eq("externalService"), any())).thenAnswer(call -> { exact.accept(call.getArgument(1)); return target; });
                when(filtered.update(eq("externalService"), eq("1s7"), any())).thenAnswer(call -> { exact.accept(call.getArgument(2)); return target; });
                when(filtered.delete(eq("externalService"), eq("1s7"), any())).thenAnswer(call -> { exact.accept(call.getArgument(2)); return target; });
                when(filtered.resourceAction(eq("externalService"), any())).thenAnswer(call -> { exact.accept(call.getArgument(1)); return target; });
                assertEquals(descriptor.body(parameters), f.domain.prepare(f.request, descriptor, descriptor.createsResource() ? null : "1s7", parameters));
                assertSame(target, f.domain.dispatch(f.request, descriptor, descriptor.createsResource() ? null : "1s7", parameters, "plan"));
                assertSame(f.request, ApiContext.getContext().getApiRequest());
            }
            verify(filtered, times(1)).create(eq("externalService"), any());
            verify(filtered, times(2)).update(eq("externalService"), eq("1s7"), any());
            verify(filtered, times(2)).resourceAction(eq("externalService"), any());
            verify(filtered, times(1)).delete(eq("externalService"), eq("1s7"), any());
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void externalFieldRoleRevisionAndCrossKindOrProjectReadsFailBeforeTheNativeMutation() throws Exception {
        Fixture f = new Fixture(); when(f.schemas.getSchema("externalService")).thenReturn(f.schema);
        FieldImpl hostname = new FieldImpl(); hostname.setUpdate(false);
        when(f.schema.getResourceFields()).thenReturn(Map.of("hostname", hostname));
        var descriptor = AiResourceChangeDescriptor.require("externalService", "update_targets");
        assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor, "1s7", Map.of("hostname", "db.test"))).getCode());
        verify(f.validation, never()).generate(any());
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            hostname.setUpdate(true); FieldImpl ips = new FieldImpl(); ips.setUpdate(true);
            when(f.schema.getResourceFields()).thenReturn(Map.of("hostname", hostname, "externalIpAddresses", ips));
            when(f.http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("2");
            assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor, "1s7", Map.of("hostname", "db.test"))).getCode());
            when(f.http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
            ResourceManager manager = mock(ResourceManager.class); f.domain.managers = mock(ResourceManagerLocator.class);
            when(f.domain.managers.getResourceManagerByType("externalService")).thenReturn(manager);
            ServiceRecord row = new ServiceRecord(); row.setId(7L); row.setKind("service"); row.setAccountId(77L);
            when(manager.getById(eq("externalService"), eq("1s7"), any())).thenReturn(row);
            assertEquals("NotFound", assertThrows(ClientVisibleException.class, () -> f.domain.visible(f.request, descriptor, "1s7")).getCode());
            row.setKind("externalService"); row.setAccountId(88L);
            assertEquals("NotFound", assertThrows(ClientVisibleException.class, () -> f.domain.visible(f.request, descriptor, "1s7")).getCode());
            row.setAccountId(77L); assertSame(row, f.domain.visible(f.request, descriptor, "1s7"));
            verify(manager, never()).update(anyString(), anyString(), any());
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void copiedDomainUsesRealRevisionGuardAndRestoresOriginalContext() throws Exception {
        Fixture f = new Fixture(); Object before = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            var descriptor = AiResourceChangeDescriptor.require("service", "update_metadata");
            assertEquals(Map.of("name", "after"), f.domain.prepare(f.request, descriptor, "1s7", Map.of("name", "after")));
            assertSame(f.request, ApiContext.getContext().getApiRequest()); assertSame(f.policy, ApiContext.getContext().getPolicy());
            assertFalse(AiResourceChangeDomain.trustedDomain(f.request));
            for (String revision : Arrays.asList(null, "2")) {
                when(f.http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn(revision);
                assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class,
                        () -> f.domain.prepare(f.request, descriptor, "1s7", Map.of("name", "after"))).getCode());
                assertSame(f.request, ApiContext.getContext().getApiRequest()); assertSame(f.policy, ApiContext.getContext().getPolicy());
            }
        } finally {
            if (before == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", before);
        }
    }

    @Test public void currentFieldCapabilityAndManagerVisibilityCannotBeBorrowedFromTheSyntheticType() throws Exception {
        Fixture f = new Fixture(); f.name.setUpdate(false);
        assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, AiResourceChangeDescriptor.require("service", "update_metadata"), "1s7", Map.of("name", "after"))).getCode());
        verify(f.validation, never()).generate(any());
    }

    @Test public void archiveUsesOnlyTheOrdinaryArchivedFieldCapabilityAndFilteredPut() throws Exception {
        Fixture f = new Fixture(); FieldImpl archived = new FieldImpl(); archived.setUpdate(false);
        when(f.schema.getResourceFields()).thenReturn(Map.of("archived", archived));
        var descriptor = AiResourceChangeDescriptor.require("service", "archive");
        assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor, "1s7", Map.of())).getCode());
        verify(f.validation, never()).generate(any());
        archived.setUpdate(true);
        Object before = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            assertEquals(Map.of("archived", true), f.domain.prepare(f.request, descriptor, "1s7", Map.of()));
            ResourceManager filtered = mock(ResourceManager.class); f.domain.managers = mock(ResourceManagerLocator.class);
            when(f.domain.managers.getResourceManagerByType("service")).thenReturn(filtered);
            ServiceRecord target = new ServiceRecord(); target.setId(7L); target.setState("inactive");
            when(filtered.update(eq("service"), eq("1s7"), any())).thenAnswer(call -> {
                ApiRequest actual = call.getArgument(2);
                assertEquals("PUT", actual.getMethod()); assertNull(actual.getAction());
                assertEquals(Map.of("archived", true), actual.getRequestObject());
                assertSame(actual, ApiContext.getContext().getApiRequest());
                return target;
            });
            assertSame(target, f.domain.dispatch(f.request, descriptor, "1s7", Map.of(), "plan"));
            verify(filtered, never()).resourceAction(anyString(), any()); verify(filtered, never()).delete(anyString(), anyString(), any());
            assertSame(f.request, ApiContext.getContext().getApiRequest());
        } finally {
            if (before == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", before);
        }
    }

    @Test public void archiveSummaryAndReceiptObserveTheBooleanWithoutChangingLifecycleOrChildFlags() {
        AiResourceChangeDomain domain = new AiResourceChangeDomain(); domain.objects = mock(ObjectManager.class);
        for (String type : List.of("stack", "service")) {
            Object target = "stack".equals(type) ? new StackRecord() : new ServiceRecord();
            var archive = AiResourceChangeDescriptor.require(type, "archive");
            var restore = AiResourceChangeDescriptor.require(type, "restore");
            assertEquals(List.of(Map.of("field", "archived", "before", false, "after", true)), domain.changes(archive, target, Map.of()));
            assertFalse(domain.complete(archive, target, Map.of(), Map.of()));
            assertTrue(domain.complete(restore, target, Map.of(), Map.of()));
            DataAccessor.setField(target, "archived", true);
            assertEquals(List.of(Map.of("field", "archived", "before", true, "after", true)), domain.changes(archive, target, Map.of()));
            assertEquals(List.of(Map.of("field", "archived", "before", true, "after", false)), domain.changes(restore, target, Map.of()));
            assertTrue(domain.complete(archive, target, Map.of(), Map.of()));
            assertFalse(domain.complete(restore, target, Map.of(), Map.of()));
        }
        verifyNoInteractions(domain.objects);
    }

    @Test public void removalWaitsForOldPortsAndAttachmentsRatherThanOnlyTheRemovedRoot() {
        AiResourceChangeDomain domain = new AiResourceChangeDomain(); domain.objects = mock(ObjectManager.class);
        InstanceRecord instance = new InstanceRecord(); instance.setId(7L); instance.setState("removed"); instance.setRemoved(new Date());
        PortRecord port = new PortRecord(); port.setInstanceId(7L);
        when(domain.objects.find(Port.class, "instanceId", 7L, "removed", null)).thenReturn(List.of(port));
        var descriptor = AiResourceChangeDescriptor.require("container", "remove");
        assertFalse(domain.complete(descriptor, instance, Map.of(), Map.of()));
        when(domain.objects.find(Port.class, "instanceId", 7L, "removed", null)).thenReturn(List.of());
        assertTrue(domain.complete(descriptor, instance, Map.of(), Map.of()));
    }

    @Test public void stackStopUsesChildrenAndAnEmptyStackIsExplicitlySummarized() {
        AiResourceChangeDomain domain = new AiResourceChangeDomain(); domain.objects = mock(ObjectManager.class); domain.json = new JacksonJsonMapper();
        StackRecord stack = new StackRecord(); stack.setId(3L); stack.setState("active");
        ServiceRecord child = new ServiceRecord(); child.setId(7L); child.setState("active");
        when(domain.objects.find(Service.class, "stackId", 3L, "removed", null)).thenReturn(List.of(child));
        var descriptor = AiResourceChangeDescriptor.require("stack", "stop");
        Map<String, Object> before = Map.of("childServices", List.of(Map.of("id", 7)));
        assertFalse(domain.complete(descriptor, stack, Map.of(), before));
        child.setState("inactive"); assertTrue(domain.complete(descriptor, stack, Map.of(), before));
        when(domain.objects.find(Service.class, "stackId", 3L, "removed", null)).thenReturn(List.of());
        assertFalse(domain.complete(descriptor, stack, Map.of(), before));
        assertTrue(domain.complete(descriptor, stack, Map.of(), Map.of("childServices", List.of())));
        assertTrue(domain.changes(descriptor, stack, Map.of()).stream().anyMatch(change -> "emptyStack".equals(change.get("field")) &&
                Boolean.TRUE.equals(change.get("after"))));
        assertTrue(domain.changes(descriptor, stack, Map.of()).stream().anyMatch(change -> "childServiceState".equals(change.get("field")) &&
                "inactive".equals(change.get("after"))));
        assertFalse(domain.changes(descriptor, stack, Map.of()).stream().anyMatch(change -> "state".equals(change.get("field"))));
    }

    @Test public void removalSummaryCountsManagedInstancesAndMountsWithoutPromisingDataDeletion() {
        AiResourceChangeDomain domain = new AiResourceChangeDomain(); domain.objects = mock(ObjectManager.class);
        StackRecord stack = new StackRecord(); stack.setId(3L); stack.setState("active");
        ServiceRecord service = new ServiceRecord(); service.setId(7L);
        ServiceExposeMapRecord managed = new ServiceExposeMapRecord(); managed.setManaged(true); managed.setInstanceId(10L);
        ServiceExposeMapRecord unmanaged = new ServiceExposeMapRecord(); unmanaged.setManaged(false); unmanaged.setInstanceId(11L);
        MountRecord mount = new MountRecord(); mount.setId(20L); mount.setVolumeId(30L); mount.setPath("private-mounted-path");
        when(domain.objects.find(Service.class, "stackId", 3L, "removed", null)).thenReturn(List.of(service));
        when(domain.objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(managed, managed, unmanaged));
        when(domain.objects.find(Mount.class, "instanceId", 10L, "removed", null)).thenReturn(List.of(mount));
        var changes = domain.changes(AiResourceChangeDescriptor.require("stack", "remove"), stack, Map.of());
        assertEquals(List.of("state", "affectedServiceCount", "affectedInstanceCount", "affectedMountCount", "mountedDataRule"),
                changes.stream().map(change -> change.get("field")).toList());
        assertEquals("removed", changes.get(0).get("after"));
        for (int index : List.of(1, 2, 3)) assertEquals(1, changes.get(index).get("before"));
        assertEquals(0, changes.get(2).get("after"));
        assertTrue(changes.get(3).containsKey("after")); assertNull(changes.get(3).get("after"));
        assertEquals("EXISTING_DOMAIN_RETENTION_NO_ADDITIONAL_PURGE", changes.get(4).get("after"));
        assertFalse(changes.toString().contains("private-mounted-path"));
        verify(domain.objects, never()).find(Mount.class, "instanceId", 11L, "removed", null);
        assertEquals("running", domain.changes(AiResourceChangeDescriptor.require("container", "start"), instance(10L), Map.of()).get(0).get("after"));
        assertEquals("operation", domain.changes(AiResourceChangeDescriptor.require("service", "restart"), service, Map.of()).get(0).get("field"));
        assertEquals("restart", domain.changes(AiResourceChangeDescriptor.require("service", "restart"), service, Map.of()).get(0).get("after"));
    }

    @Test public void scaleAndRestartNeedTheActualHealthyInstanceCountsAndRestartEvidence() {
        AiResourceChangeDomain domain = new AiResourceChangeDomain(); domain.objects = mock(ObjectManager.class);
        domain.deploymentReadiness = mock(ServiceDeploymentReadiness.class);
        ServiceRecord service = new ServiceRecord(); service.setId(7L); service.setAccountId(77L); service.setState("active");
        service.setData(Map.of("fields", Map.of("scale", 2)));
        ServiceExposeMapRecord first = new ServiceExposeMapRecord(); first.setManaged(true); first.setInstanceId(10L);
        ServiceExposeMapRecord second = new ServiceExposeMapRecord(); second.setManaged(true); second.setInstanceId(11L);
        InstanceRecord a = instance(10), b = instance(11);
        when(domain.objects.loadResource(Instance.class, 10L)).thenReturn(a); when(domain.objects.loadResource(Instance.class, 11L)).thenReturn(b);
        when(domain.objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(first));
        assertFalse(domain.complete(AiResourceChangeDescriptor.require("service", "scale"), service, Map.of("scale", 2), Map.of()));
        when(domain.objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(first, second));
        when(domain.deploymentReadiness.isReady(service)).thenReturn(true);
        assertTrue(domain.complete(AiResourceChangeDescriptor.require("service", "scale"), service, Map.of("scale", 2), Map.of()));
        var restart = AiResourceChangeDescriptor.require("service", "restart");
        Map<String, Object> before = Map.of("managedInstances", List.of(Map.of("id", 10L, "startCount", 1), Map.of("id", 11L, "startCount", 1)));
        assertFalse(domain.complete(restart, service, Map.of(), before));
        a.setStartCount(2L); assertFalse(domain.complete(restart, service, Map.of(), before));
        b.setStartCount(2L); assertTrue(domain.complete(restart, service, Map.of(), before));
    }

    @Test public void scaleDownWaitsForSurplusInstancesMapsPortsNicsAndMountRetirement() {
        AiResourceChangeDomain domain = new AiResourceChangeDomain(); domain.objects = mock(ObjectManager.class);
        domain.deploymentReadiness = mock(ServiceDeploymentReadiness.class);
        ServiceRecord service = new ServiceRecord(); service.setId(7L); service.setAccountId(77L); service.setState("active");
        service.setData(Map.of("fields", Map.of("scale", 1)));
        ServiceExposeMapRecord first = new ServiceExposeMapRecord(); first.setManaged(true); first.setInstanceId(10L);
        ServiceExposeMapRecord retired = new ServiceExposeMapRecord(); retired.setManaged(true); retired.setInstanceId(11L);
        InstanceRecord a = instance(10), b = instance(11);
        when(domain.objects.loadResource(Instance.class, 10L)).thenReturn(a);
        when(domain.objects.loadResource(Instance.class, 11L)).thenReturn(b);
        when(domain.objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(first, retired));
        var descriptor = AiResourceChangeDescriptor.require("service", "scale");
        Map<String, Object> before = Map.of("managedInstances", List.of(Map.of("id", 10L), Map.of("id", 11L)));
        assertFalse(domain.complete(descriptor, service, Map.of("scale", 1), before));
        when(domain.deploymentReadiness.isReady(service)).thenReturn(true);
        when(domain.objects.find(ServiceExposeMap.class, "serviceId", 7L, "removed", null)).thenReturn(List.of(first));
        assertFalse(domain.complete(descriptor, service, Map.of("scale", 1), before));
        b.setRemoved(new Date()); b.setState("removed");
        when(domain.objects.find(ServiceExposeMap.class, "instanceId", 11L, "removed", null)).thenReturn(List.of(retired));
        assertFalse(domain.complete(descriptor, service, Map.of("scale", 1), before));
        when(domain.objects.find(ServiceExposeMap.class, "instanceId", 11L, "removed", null)).thenReturn(List.of());
        when(domain.objects.find(Port.class, "instanceId", 11L, "removed", null)).thenReturn(List.of(new PortRecord()));
        assertFalse(domain.complete(descriptor, service, Map.of("scale", 1), before));
        when(domain.objects.find(Port.class, "instanceId", 11L, "removed", null)).thenReturn(List.of());
        when(domain.objects.find(Nic.class, "instanceId", 11L, "removed", null)).thenReturn(List.of(new NicRecord()));
        assertFalse(domain.complete(descriptor, service, Map.of("scale", 1), before));
        when(domain.objects.find(Nic.class, "instanceId", 11L, "removed", null)).thenReturn(List.of());
        MountRecord mount = new MountRecord(); mount.setState("deactivating");
        when(domain.objects.find(Mount.class, "instanceId", 11L, "removed", null)).thenReturn(List.of(mount));
        assertFalse(domain.complete(descriptor, service, Map.of("scale", 1), before));
        mount.setState("inactive"); assertTrue(domain.complete(descriptor, service, Map.of("scale", 1), before));
    }

    @Test public void manualScaleRejectsUnsupportedDeploymentModeWithoutBlockingItsOtherLifecycleActions() {
        AiResourceChangeDomain domain = new AiResourceChangeDomain(); domain.deploymentReadiness = mock(ServiceDeploymentReadiness.class);
        ServiceRecord service = new ServiceRecord(); service.setState("active");
        var scale = AiResourceChangeDescriptor.require("service", "scale");
        var rejected = assertThrows(ClientVisibleException.class, () -> domain.ready(scale, service, null));
        assertEquals(422, rejected.getStatus()); assertEquals("ResourceChangeUnsupported", rejected.getCode());
        domain.ready(AiResourceChangeDescriptor.require("service", "stop"), service, null);
        domain.ready(AiResourceChangeDescriptor.require("service", "restart"), service, null);
        service.setState("inactive"); domain.ready(AiResourceChangeDescriptor.require("service", "start"), service, null);
        service.setState("active"); when(domain.deploymentReadiness.supportsManualScale(service)).thenReturn(true);
        domain.ready(scale, service, null);
        verify(domain.deploymentReadiness, times(2)).supportsManualScale(service);
        verifyNoMoreInteractions(domain.deploymentReadiness);
    }

    @Test public void lockingReadKeepsOrdinaryUiUpdatesOutsideTheAtomicDispatchWindow() throws Exception {
        try (Database database = new Database(); Connection ai = database.open(); Connection ui = database.open(); var pool =
                Executors.newSingleThreadExecutor()) {
            AiResourceChangeDomain domain = new AiResourceChangeDomain(); domain.setConfiguration(DSL.using(ai, SQLDialect.H2, new
                    Settings().withRenderSchema(false)).configuration());
            assertEquals("old", ((Service) domain.lock("service", 7)).getDescription());
            CountDownLatch attempted = new CountDownLatch(1);
            var write = pool.submit(() -> { attempted.countDown(); return
                    ui.createStatement().executeUpdate("UPDATE service SET description='ui' WHERE id=7"); });
            assertTrue(attempted.await(2, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> write.get(150, TimeUnit.MILLISECONDS));
            ai.commit(); assertEquals(1, (int) write.get(3, TimeUnit.SECONDS)); ui.commit();
        }
    }

    @Test public void stopMustObserveOriginalInstancesEvenAfterTheirServiceMapsRetire() {
        AiResourceChangeDomain domain = new AiResourceChangeDomain(); domain.objects = mock(ObjectManager.class);
        ServiceRecord service = new ServiceRecord(); service.setId(7L); service.setAccountId(77L); service.setState("inactive");
        StackRecord stack = new StackRecord(); stack.setId(3L); stack.setState("active");
        InstanceRecord instance = instance(10L);
        when(domain.objects.loadResource(Instance.class, 10L)).thenReturn(instance);
        when(domain.objects.find(Service.class, "stackId", 3L, "removed", null)).thenReturn(List.of(service));
        Map<String, Object> beforeService = Map.of("id", 7L, "managedInstances", List.of(Map.of("id", 10L)));
        Map<String, Object> beforeStack = Map.of("childServices", List.of(beforeService));
        var serviceStop = AiResourceChangeDescriptor.require("service", "stop");
        var stackStop = AiResourceChangeDescriptor.require("stack", "stop");
        assertFalse(domain.complete(serviceStop, service, Map.of(), beforeService));
        assertFalse(domain.complete(stackStop, stack, Map.of(), beforeStack));
        instance.setState("stopped");
        assertTrue(domain.complete(serviceStop, service, Map.of(), beforeService));
        assertTrue(domain.complete(stackStop, stack, Map.of(), beforeStack));
        instance.setRemoved(new Date()); instance.setState("removed");
        when(domain.objects.find(Port.class, "instanceId", 10L, "removed", null)).thenReturn(List.of(new PortRecord()));
        assertFalse(domain.complete(serviceStop, service, Map.of(), beforeService));
        assertFalse(domain.complete(stackStop, stack, Map.of(), beforeStack));
        when(domain.objects.find(Port.class, "instanceId", 10L, "removed", null)).thenReturn(List.of());
        assertTrue(domain.complete(serviceStop, service, Map.of(), beforeService));
        assertTrue(domain.complete(stackStop, stack, Map.of(), beforeStack));
        instance.setAccountId(88L); assertFalse(domain.complete(serviceStop, service, Map.of(), beforeService));
    }

    @Test public void earlierUiUpdateWinsAndAutocommitCannotPretendToHoldTheCasLock() throws Exception {
        try (Database database = new Database(); Connection ai = database.open()) {
            database.owner.createStatement().executeUpdate("UPDATE service SET description='ui' WHERE id=7");
            AiResourceChangeDomain domain = new AiResourceChangeDomain(); domain.setConfiguration(DSL.using(ai, SQLDialect.H2, new
                    Settings().withRenderSchema(false)).configuration());
            assertEquals("ui", ((Service) domain.lock("service", 7)).getDescription()); ai.commit(); ai.setAutoCommit(true);
            assertEquals("ResourceChangeTransactionRequired", assertThrows(ClientVisibleException.class, () -> domain.lock("service", 7)).getCode());
        }
    }

    private static InstanceRecord instance(long id) {
        InstanceRecord result = new InstanceRecord(); result.setId(id); result.setAccountId(77L); result.setState("running");
                result.setHealthState("healthy"); result.setStartCount(1L); return result;
    }
    private static class Database implements AutoCloseable {
        final String url = "jdbc:h2:mem:change-lock-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000";
        final Connection owner;
        Database() throws Exception {
            owner = DriverManager.getConnection(url);
            var db = DSL.using(owner, SQLDialect.H2, new Settings().withRenderSchema(false));
            db.createTable(SERVICE).columns(Arrays.stream(SERVICE.fields()).map(field -> DSL.field(field.getUnqualifiedName(),
                    field.getDataType().getSQLDataType().nullable(true))).toList()).execute();
            db.insertInto(SERVICE).set(SERVICE.ID, 7L).set(SERVICE.ACCOUNT_ID, 77L).set(SERVICE.KIND, "service").set(SERVICE.STATE,
                    "active").set(SERVICE.DESCRIPTION, "old").execute();
        }
        Connection open() throws Exception { Connection result = DriverManager.getConnection(url); result.setAutoCommit(false); return result; }
        @Override public void close() throws Exception { owner.close(); }
    }

    @Test public void aliasReferenceReadsKeepTheActualTypeAndStackAuthorityAndRejectSourceChangesBeforeMutation() throws Exception {
        Fixture f = aliasFixture(); var descriptor = AiResourceChangeDescriptor.require("dnsService", "update_targets");
        Map<String, Object> parameters = Map.of("serviceIds", List.of("1s9"));
        Object before = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            Map<String, Object> refs = f.domain.aliasReferenceSnapshot(f.request, descriptor, "1s7", parameters);
            Map<String, Object> plan = Map.of("referenceSnapshot", refs);
            assertEquals(Map.of("serviceLinks", List.of(Map.of("serviceId", "1s9"))),
                    f.domain.prepare(f.request, descriptor, "1s7", parameters, plan));
            ResourceManager manager = f.domain.managers.getResourceManagerByType("dnsService");
            when(manager.resourceAction(eq("dnsService"), any())).thenAnswer(call -> {
                ApiRequest actual = call.getArgument(1); assertEquals("setservicelinks", actual.getAction());
                assertEquals(Map.of("serviceLinks", List.of(Map.of("serviceId", "1s9"))), actual.getRequestObject());
                assertTrue(AiResourceChangeDomain.trustedDomain(actual)); return f.domain.objects.loadResource(Service.class, 7L);
            });
            assertNotNull(f.domain.dispatch(f.request, descriptor, "1s7", parameters, "own-plan"));
            ServiceRecord target = (ServiceRecord) f.domain.objects.loadResource(Service.class, 9L);
            target.setUuid("replacement");
            assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor, "1s7", parameters, plan)).getCode()); target.setUuid("target");
            var stack = (StackRecord) f.domain.objects.loadResource(io.cattle.platform.core.model.Stack.class, 3L);
            stack.setUuid("replacement-stack");
            assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor, "1s7", parameters, plan)).getCode()); stack.setUuid("stack");
            when(f.schema.getResourceMethods()).thenReturn(List.of());
            assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor, "1s7", parameters, plan)).getCode());
            when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT", "DELETE")); target.setAccountId(88L);
            assertEquals("NotFound", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor, "1s7", parameters, plan)).getCode());
            verify(manager, times(1)).resourceAction(eq("dnsService"), any());
            assertSame(f.request, ApiContext.getContext().getApiRequest());
        } finally {
            if (before == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", before);
        }
    }

    @Test public void aliasSelfAdvancedLinksAndIncomingConsumersStopBeforeAnyRemovalDispatch() {
        Fixture f = aliasFixture(); ServiceRecord alias = (ServiceRecord) f.domain.objects.loadResource(Service.class, 7L);
        var descriptor = AiResourceChangeDescriptor.require("dnsService", "remove");
        var link = new io.cattle.platform.core.model.tables.records.ServiceConsumeMapRecord();
        link.setId(11L); link.setServiceId(7L); link.setConsumedServiceId(7L); link.setAccountId(77L);
        when(f.domain.objects.find(io.cattle.platform.core.model.ServiceConsumeMap.class, "serviceId", 7L, "removed", null))
                .thenReturn(List.of(link));
        assertEquals("ResourceChangeUnsupported", assertThrows(ClientVisibleException.class,
                () -> f.domain.ready(descriptor, alias, null)).getCode());
        link.setConsumedServiceId(9L); link.setName("advanced-alias");
        assertEquals("ResourceChangeUnsupported", assertThrows(ClientVisibleException.class,
                () -> f.domain.ready(descriptor, alias, null)).getCode());
        link.setName(null);
        when(f.domain.objects.find(io.cattle.platform.core.model.ServiceConsumeMap.class, "consumedServiceId", 7L, "removed", null))
                .thenReturn(List.of(link));
        assertEquals("ResourceChangeUnsupported", assertThrows(ClientVisibleException.class,
                () -> f.domain.ready(descriptor, alias, null)).getCode());
        verify(f.domain.managers.getResourceManagerByType("dnsService"), never()).delete(anyString(), anyString(), any());
    }

    private static Fixture aliasFixture() {
        Fixture f = new Fixture(); when(f.schemas.getSchema("dnsService")).thenReturn(f.schema);
        when(f.schemas.getSchema("stack")).thenReturn(f.schema);
        when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT", "DELETE"));
        when(f.schema.getResourceActions()).thenReturn(Map.of("setservicelinks", mock(io.github.ibuildthecloud.gdapi.model.Action.class)));
        f.domain.targets = mock(io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver.class);
        when(f.domain.targets.parseScopeId(anyString(), anyString())).thenAnswer(call ->
                Long.parseLong(((String) call.getArgument(1)).replaceFirst("^1(?:st|s)", "")));
        var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(f.schemas);
        when(f.schemas.getBaseType("dnsService")).thenReturn("service"); formatter.setTypeMappings(Map.of("service", "s", "stack", "st"));
        ApiContext.getContext().setIdFormatter(formatter);
        var alias = new ServiceRecord(); alias.setId(7L); alias.setUuid("alias"); alias.setKind("dnsService");
        alias.setAccountId(77L); alias.setStackId(3L); alias.setName("alias"); alias.setState("inactive");
        var target = new ServiceRecord(); target.setId(9L); target.setUuid("target"); target.setKind("service");
        target.setAccountId(77L); target.setStackId(3L); target.setName("db"); target.setState("active");
        var stack = new StackRecord(); stack.setId(3L); stack.setUuid("stack"); stack.setKind("stack"); stack.setAccountId(77L); stack.setName("Blue");
        when(f.domain.objects.loadResource(Service.class, 7L)).thenReturn(alias); when(f.domain.objects.loadResource(Service.class, 9L)).thenReturn(target);
        when(f.domain.objects.loadResource(io.cattle.platform.core.model.Stack.class, 3L)).thenReturn(stack);
        f.domain.managers = mock(ResourceManagerLocator.class); var manager = mock(ResourceManager.class);
        when(f.domain.managers.getResourceManagerByType(anyString())).thenReturn(manager);
        when(manager.getById(eq("dnsService"), eq("1s7"), any())).thenReturn(alias);
        when(manager.getById(eq("service"), eq("1s9"), any())).thenReturn(target);
        when(manager.getById(eq("stack"), eq("1st3"), any())).thenReturn(stack);
        return f;
    }

    private static class Fixture {
        final AiResourceChangeDomain domain = new AiResourceChangeDomain();
        final Policy policy = mock(Policy.class); final SchemaFactory schemas = mock(SchemaFactory.class);
        final Schema schema = mock(Schema.class); final FieldImpl name = new FieldImpl();
        final HttpServletRequest http = mock(HttpServletRequest.class);
        final ApiRequest request = new ApiRequest(new ApiServletContext(http, null, null), schemas);
        final ValidationHandler validation = mock(ValidationHandler.class);
        Fixture() {
            when(policy.getAuthenticatedAsAccountId()).thenReturn(42L); when(policy.getAccountId()).thenReturn(77L);
            domain.processObserver = new AiResourceChangeProcessObserver() {
                @Override public <T> T dispatch(ApiRequest actual, String id, java.util.function.Supplier<T> operation) { return operation.get(); }
            };
            domain.keys = mock(AiKeyAuthorization.class); when(domain.keys.actor(any())).thenReturn(policy);
            AccountRecord actor = new AccountRecord(); actor.setId(42L); actor.setState("active");
            AccountRecord project = new AccountRecord(); project.setId(77L); project.setState("active"); project.setKind("project");
            domain.objects = mock(ObjectManager.class); when(domain.objects.loadResource(Account.class, 42L)).thenReturn(actor);
                    when(domain.objects.loadResource(Account.class, 77L)).thenReturn(project);
            domain.authenticator = mock(ApiAuthenticator.class); when(domain.authenticator.currentAuthorization(eq(42L), eq(77L),
                    any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(policy, schemas));
            name.setCreate(true); name.setUpdate(true); when(schema.getResourceFields()).thenReturn(Map.of("name", name));
                    when(schemas.getSchema("service")).thenReturn(schema);
            domain.validation = validation; domain.json = new JacksonJsonMapper();
            ApiKeyPolicy bridge = new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE);
            domain.keyAccess = new ApiKeyAuthorizationService() { @Override public void authorize(ApiRequest actual, Policy owner) {
                    ApiKeyAiBridgeGuard.check(actual, bridge); } };
            request.setType("aiResourceChangePlan"); request.setMethod("POST"); request.setRequestObject(Map.of());
            when(http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
            ApiContext.newContext().setPolicy(policy); ApiContext.getContext().setApiRequest(request);
            ApiKeyCredentialContext.attach(request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, bridge));
        }
    }
}
