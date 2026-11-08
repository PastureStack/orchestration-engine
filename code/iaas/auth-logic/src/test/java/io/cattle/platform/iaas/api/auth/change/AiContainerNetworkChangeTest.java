package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.InstanceLinkRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.PortRecord;
import io.cattle.platform.iaas.api.port.PortUpdateService;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AiContainerNetworkChangeTest {
    private final JacksonJsonMapper json = new JacksonJsonMapper();
    private Object previousBridgeEnabled;
    @Before public void setup() {
        previousBridgeEnabled = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
    }
    @After public void cleanup() {
        if (previousBridgeEnabled == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previousBridgeEnabled);
        ApiContext.remove();
    }

    @Test public void oneExistingChildHasAnExactNativeBodyAndNoOtherWritableFields() {
        var port = AiResourceChangeDescriptor.require("port", "update_public_port");
        Map<String, Object> clear = new LinkedHashMap<>(); clear.put("publicPort", null);
        assertEquals(clear, port.body(port.parameters(selection(clear))));
        assertEquals(Map.of("publicPort", 65535), port.body(port.parameters(selection(Map.of("publicPort", 65535)))));
        for (Object bad : List.of(0, 65536, -1, 1.5, Double.NaN, "8080", true))
            assertThrows(ClientVisibleException.class, () -> port.parameters(selection(Map.of("publicPort", bad))));
        for (String field : List.of("instanceId", "privatePort", "protocol", "bindAddress", "ports", "name"))
            assertThrows(ClientVisibleException.class, () -> port.parameters(selection(Map.of("publicPort", 8080, field, 7))));
        for (String operation : List.of("create", "remove", "update_metadata", "stop", "restart"))
            assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("port", operation));
        var link = AiResourceChangeDescriptor.require("instanceLink", "update_target");
        assertEquals(Map.of("targetInstanceId", "1i4"), link.body(link.parameters(selection(
                Map.of("targetInstanceId", "1i4", "targetInstanceUuid", "target-uuid")))));
        for (Object bad : List.of("", 7, List.of("1i4")))
            assertThrows(ClientVisibleException.class, () -> link.parameters(selection(Map.of("targetInstanceId", bad, "targetInstanceUuid", "target-uuid"))));
        assertThrows(ClientVisibleException.class, () -> link.parameters(clear));
    }

    @Test public void shippedNativePlanSchemaAdmitsOnlyTheNewTypedOperationsAndKeepsPinsInsideExistingJsonParameters() throws Exception {
        Path root = Path.of(System.getProperty("containerNetwork.sourceRoot", ".")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/base/aiResourceChangePlan.json"))) root = root.getParent();
        assertNotNull(root);
        Map<?, ?> schema = json.readValue(Files.readString(root.resolve("resources/content/schema/base/aiResourceChangePlan.json")));
        Map<?, ?> fields = (Map<?, ?>) schema.get("resourceFields");
        assertTrue(((List<?>) ((Map<?, ?>) fields.get("resourceType")).get("options")).containsAll(List.of("port", "instanceLink")));
        assertTrue(((List<?>) ((Map<?, ?>) fields.get("operation")).get("options")).containsAll(List.of("update_public_port", "update_target")));
        assertEquals("json", ((Map<?, ?>) fields.get("parameters")).get("type"));
        assertFalse(fields.containsKey("sourceId")); assertFalse(fields.containsKey("resourceUuid"));
        assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("instanceLink", "remove"));
    }

    @Test public void nativeUiChildChangeAndUuidOrSourceRebindInvalidateTheActualPlanBeforeClaim() {
        for (String mutation : List.of("publicPort", "childUuid", "sourceUuid", "sourceConfig", "sourceParent")) {
            Fixture f = new Fixture(); var plan = f.preview(); assertEquals("pending", plan.get("confirmationState"));
            f.owner.confirm(); assertEquals(0, f.owner.claims); assertEquals(0, f.owner.dispatched);
            switch (mutation) {
            case "publicPort" -> f.port.setPublicPort(9090);
            case "childUuid" -> f.port.setUuid("other-child-uuid");
            case "sourceUuid" -> f.source.setUuid("other-source-uuid");
            case "sourceConfig" -> DataAccessor.fields(f.source).withKey("environment").set(Map.of("CHANGED", "SECRET-CANARY"));
            default -> f.port.setInstanceId(4L);
            }
            assertThrows(ClientVisibleException.class, () -> f.owner.apply(false));
            assertEquals(mutation, 0, f.owner.claims); assertEquals(mutation, 0, f.owner.dispatched);
        }
    }

    @Test public void oneApprovedDispatchIsNeverReplayedAndTerminalIdentityRetainsTheChildAndParent() {
        Fixture f = new Fixture(); f.preview(); f.owner.confirm();
        assertEquals("accepted", f.owner.apply(false).get("status"));
        assertEquals("running", f.owner.apply(false).get("status"));
        assertEquals(1, f.owner.claims); assertEquals(1, f.owner.dispatched);
        f.owner.complete = true; f.owner.service.get(f.owner.id, f.owner.request(null, false));
        assertEquals("succeeded", f.owner.documents.get(f.owner.id).get("status"));
        Map<?, ?> identity = (Map<?, ?>) f.owner.documents.get(f.owner.id).get("containerNetworkIdentity");
        assertEquals("child-uuid", identity.get("uuid")); assertEquals("source-uuid", identity.get("sourceUuid"));
        assertEquals("adminProject", identity.get("projectUuid"));
        assertFalse(f.owner.documents.get(f.owner.id).containsKey("snapshot"));
        f = new Fixture(); f.preview(); f.owner.confirm(); f.owner.failDispatch = true;
        assertEquals("unknown", f.owner.apply(false).get("status"));
        assertEquals("unknown", f.owner.apply(false).get("status")); assertEquals(1, f.owner.dispatched);
    }

    @Test public void currentNativeRoleFieldKeyAndValidatorCeilingsCannotBeBypassed() throws Exception {
        for (String denial : List.of("method", "field", "boundKey", "validatorRewrite")) {
            Native f = new Native(); var descriptor = AiResourceChangeDescriptor.require("port", "update_public_port");
            if ("method".equals(denial)) when(f.fixture.schema.getResourceMethods()).thenReturn(List.of("GET"));
            if ("field".equals(denial)) f.capability.setUpdate(false);
            if ("boundKey".equals(denial)) doThrow(new ClientVisibleException(403, "ApiKeyPolicyChanged"))
                    .when(f.domain.keys).authorizeBoundDomain(anyMap(), any());
            if ("validatorRewrite".equals(denial)) doAnswer(call -> { ((ApiRequest) call.getArgument(0)).setRequestObject(Map.of()); return null; })
                    .when(f.domain.validation).generate(any());
            var error = assertThrows(ClientVisibleException.class, () ->
                    f.domain.prepare(f.fixture.request, descriptor, "1p7", Map.of("publicPort", 8080), Map.of()));
            assertEquals("boundKey".equals(denial) ? "ApiKeyPolicyChanged" : "PermissionDenied", error.getCode());
            verify(f.manager, never()).update(anyString(), anyString(), any());
            verify(f.domain.containerPortUpdates, never()).preview(any(), anyLong(), anyLong(), any());
        }
    }

    @Test public void normalNativeManagerOwnsTheOnlyPutAndPreflightUsesTheWholePreservedPortConfiguration() throws Exception {
        Native f = new Native(); var descriptor = AiResourceChangeDescriptor.require("port", "update_public_port");
        Map<String, Object> body = f.domain.prepare(f.fixture.request, descriptor, "1p7", Map.of("publicPort", 8080));
        assertEquals(Map.of("publicPort", 8080), body);
        assertSame(f.data.port, f.domain.dispatch(f.fixture.request, descriptor, "1p7", body, "exact-plan"));
        verify(f.manager, times(1)).update(eq("port"), eq("1p7"), argThat(request -> request.getMethod().equals("PUT")
                && request.getRequestObject().equals(Map.of("publicPort", 8080))));
        verify(f.domain.containerPortUpdates, times(2)).preview(eq(f.fixture.request), eq(7L), eq(77L), eq(8080));
        assertEquals(List.of("127.0.0.1:8080:80/tcp", "5353:53/udp"), AiContainerNetworkChange.portSpecs(f.data.ports(), f.data.port, 8080));
        assertEquals(8000, f.data.port.getPublicPort().intValue()); // Native owner alone mutates; no helper side write.
        when(f.domain.objects.loadResource(Instance.class, 3L)).thenReturn(null);
        assertEquals("PortUpdatePreconditionRequired", assertThrows(ClientVisibleException.class,
                () -> f.domain.dispatch(f.fixture.request, descriptor, "1p7", body, "missing-native-before")).getCode());
        verify(f.manager, times(1)).update(anyString(), anyString(), any());
    }

    @Test public void currentParentReadCannotUseAnOlderRepeatableReadProjectionOrBorrowChildAuthority() {
        Native f = new Native(); var descriptor = AiResourceChangeDescriptor.require("port", "update_public_port");
        ResourceManager containers = mock(ResourceManager.class);
        when(f.domain.managers.getResourceManagerByType("container")).thenReturn(containers);
        when(containers.getById(eq("container"), eq("1i3"), any())).thenReturn(f.data.source);
        doReturn(f.data.source).when(f.domain).lock("container", 3L); doReturn(f.data.project).when(f.domain).lock("project", 77L);
        doReturn("1i3").when(f.domain).publicId("container", 3L);
        doCallRealMethod().when(f.domain).containerNetworkSnapshot(any(), any(), any(), anyMap(), anyBoolean());
        f.domain.verifyContainerNetworkSelection(f.fixture.request, descriptor, f.data.port,
                Map.of("resourceUuid", "child-uuid", "sourceId", "1i3", "sourceUuid", "source-uuid"));
        assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class, () ->
                f.domain.verifyContainerNetworkSelection(f.fixture.request, descriptor, f.data.port,
                        Map.of("resourceUuid", "reused-id-new-uuid", "sourceId", "1i3", "sourceUuid", "source-uuid"))).getCode());
        assertEquals(f.data.snapshot(), f.domain.containerNetworkSnapshot(f.fixture.request, descriptor, f.data.port, Map.of("publicPort", 8080), true));
        InstanceRecord stale = instance(3, "old-uuid"); when(containers.getById(eq("container"), eq("1i3"), any())).thenReturn(stale);
        assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class, () ->
                f.domain.containerNetworkSnapshot(f.fixture.request, descriptor, f.data.port, Map.of("publicPort", 8080), true)).getCode());
        when(containers.getById(eq("container"), eq("1i3"), any())).thenThrow(new ClientVisibleException(403, "KeyScopeDenied"));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class, () ->
                f.domain.containerNetworkSnapshot(f.fixture.request, descriptor, f.data.port, Map.of("publicPort", 8080), true)).getCode());
        verify(f.manager, never()).update(anyString(), anyString(), any());
    }

    @Test public void pendingReadReauthorizesWithoutTakingNativeMutationLocks() {
        Native f = new Native(); f.fixture.request.setMethod("GET");
        var descriptor = AiResourceChangeDescriptor.require("port", "update_public_port");
        assertEquals(Map.of("publicPort", 8080), f.domain.prepare(f.fixture.request, descriptor, "1p7", Map.of("publicPort", 8080), Map.of()));
        verify(f.domain).containerNetworkSnapshot(eq(f.fixture.request), eq(descriptor), eq(f.data.port), anyMap(), eq(false));
        verifyNoInteractions(f.domain.containerPortUpdates);
    }

    @Test public void linkNativePutDoesNotEnterThePortOwnerOrChangeItsSourceAndAlias() {
        Native f = new Native(); var descriptor = AiResourceChangeDescriptor.require("instanceLink", "update_target");
        when(f.fixture.schemas.getSchema("instanceLink")).thenReturn(f.fixture.schema);
        when(f.fixture.schema.getResourceFields()).thenReturn(Map.of("targetInstanceId", f.capability));
        when(f.domain.managers.getResourceManagerByType("instanceLink")).thenReturn(f.manager);
        Map<String, Object> body = Map.of("targetInstanceId", "1i4");
        f.domain.dispatch(f.fixture.request, descriptor, "1il8", body, "exact-link-plan");
        verify(f.manager).update(eq("instanceLink"), eq("1il8"), argThat(request -> request.getRequestObject().equals(body)));
        verifyNoInteractions(f.domain.containerPortUpdates);
        verify(f.domain.objects, never()).loadResource(eq("port"), anyLong());
    }

    @Test public void runningAndStoppedConfigurationCompletionRequiresDerivedPortsAndPreservesContainerIdentity() {
        for (String state : List.of("running", "stopped")) {
            Native f = new Native(); f.data.source.setState(state);
            var descriptor = AiResourceChangeDescriptor.require("port", "update_public_port");
            Map<String, Object> before = f.data.snapshot(); f.data.port.setPublicPort(8080);
            assertFalse(f.domain.complete(descriptor, f.data.port, Map.of("publicPort", 8080), before));
            DataAccessor.fields(f.data.source).withKey("ports").set(List.of("127.0.0.1:8080:80/tcp", "5353:53/udp"));
            assertTrue(f.domain.complete(descriptor, f.data.port, Map.of("publicPort", 8080), before));
            DataAccessor.fields(f.data.source).withKey("startCount").set(2);
            assertFalse(f.domain.complete(descriptor, f.data.port, Map.of("publicPort", 8080), before));
            assertEquals("source-uuid", f.data.source.getUuid()); assertEquals(state, f.data.source.getState());
        }
    }

    @Test public void linkTargetNeedsTheExactSameProjectAndNativeHostConstraintWithoutChangingSourceOrAlias() {
        Fixture f = new Fixture(); InstanceLinkRecord link = new InstanceLinkRecord(); link.setId(8L); link.setUuid("link-uuid");
        link.setAccountId(77L); link.setInstanceId(3L); link.setTargetInstanceId(4L); link.setLinkName("db"); link.setState("active");
        InstanceRecord old = instance(4, "old"), target = instance(5, "target");
        DataAccessor.fields(f.source).withKey("requestedHostId").set(7L);
        DataAccessor.fields(f.source).withKey("networkMode").set("bridge");
        assertThrows(ClientVisibleException.class, () -> AiContainerNetworkChange.snapshot(link, f.source, f.project, old, target, 8L, json));
        var before = AiContainerNetworkChange.snapshot(link, f.source, f.project, old, target, 7L, json);
        target.setAccountId(88L); assertThrows(ClientVisibleException.class, () ->
                AiContainerNetworkChange.snapshot(link, f.source, f.project, old, target, 7L, json));
        target.setAccountId(77L); link.setTargetInstanceId(5L);
        assertTrue(AiContainerNetworkChange.childComplete(link, Map.of("targetInstanceId", "1i5"), before, json));
        link.setLinkName("changed"); assertFalse(AiContainerNetworkChange.childComplete(link, Map.of("targetInstanceId", "1i5"), before, json));
        link.setLinkName("db"); DataAccessor.fields(f.source).withKey("networkMode").set("managed");
        assertThrows(ClientVisibleException.class, () -> AiContainerNetworkChange.snapshot(link, f.source, f.project, target, f.source, 7L, json));
        DataAccessor.fields(f.source).withKey("systemContainer").set("NetworkAgent");
        assertThrows(ClientVisibleException.class, () -> AiContainerNetworkChange.snapshot(link, f.source, f.project, target, old, 7L, json));
    }

    private static InstanceRecord instance(long id, String uuid) {
        InstanceRecord row = new InstanceRecord(); row.setId(id); row.setUuid(uuid); row.setAccountId(77L);
        row.setKind("container"); row.setName("same-name"); row.setState("running"); return row;
    }

    private static Map<String, Object> selection(Map<String, Object> values) {
        Map<String, Object> selected = new LinkedHashMap<>(values);
        selected.put("resourceUuid", "child-uuid"); selected.put("sourceId", "1i3"); selected.put("sourceUuid", "source-uuid"); return selected;
    }

    static class Fixture {
        final AiResourceChangePlanServiceTest.Fixture owner = new AiResourceChangePlanServiceTest.Fixture();
        final JacksonJsonMapper json = new JacksonJsonMapper(); final InstanceRecord source = instance(3, "source-uuid");
        final AccountRecord project = new AccountRecord(); final PortRecord port = new PortRecord(); final PortRecord other = new PortRecord();
        Fixture() {
            project.setId(77L); project.setKind("project"); project.setState("active"); project.setUuid("adminProject");
            port.setId(7L); port.setUuid("child-uuid"); port.setAccountId(77L); port.setInstanceId(3L); port.setState("active");
            port.setPrivatePort(80); port.setPublicPort(8000); port.setProtocol("tcp"); DataAccessor.fields(port).withKey("bindAddress").set("127.0.0.1");
            other.setId(9L); other.setUuid("other-uuid"); other.setAccountId(77L); other.setInstanceId(3L); other.setState("active");
            other.setPrivatePort(53); other.setPublicPort(5353); other.setProtocol("udp");
            DataAccessor.fields(source).withKey("networkMode").set("managed"); DataAccessor.fields(source).withKey("startCount").set(1);
            DataAccessor.fields(source).withKey("ports").set(List.of("127.0.0.1:8000:80/tcp", "5353:53/udp"));
            when(owner.domain.lock(anyString(), anyLong())).thenReturn(port); when(owner.domain.visible(any(), any(), anyString())).thenReturn(port);
            when(owner.domain.receiptTarget(any(), any(), anyMap())).thenReturn(port);
            when(owner.domain.withContainerPortWrite(any(), anyMap(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(2)).get());
            when(owner.domain.containerNetworkSnapshot(any(), any(), any(), anyMap(), anyBoolean())).thenAnswer(call -> snapshot());
        }
        List<Port> ports() { return List.of(port, other); }
        Map<String, Object> snapshot() { return AiContainerNetworkChange.snapshot(port, source, project, null, null, null, json); }
        Map<String, Object> preview() {
            ApiRequest request = owner.request(null, false); request.setId(null);
            request.setRequestObject(Map.of("resourceType", "port", "operation", "update_public_port", "resourceId", "1p7",
                    "parameters", selection(Map.of("publicPort", 8080))));
            Map<String, Object> result = owner.service.preview(request); owner.id = (String) result.get("id"); return result;
        }
    }

    static class Native {
        final AiResourceChangeDomainTest.Fixture fixture = new AiResourceChangeDomainTest.Fixture();
        final Fixture data = new Fixture(); final AiResourceChangeDomain domain = spy(fixture.domain);
        final ResourceManager manager = mock(ResourceManager.class); final FieldImpl capability = new FieldImpl();
        Native() {
            // Fixture setup is local; restore the real Domain fixture's authenticated request after constructing plan fixtures.
            ApiContext.getContext().setPolicy(fixture.policy); ApiContext.getContext().setApiRequest(fixture.request);
            capability.setUpdate(true); when(fixture.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
            when(fixture.schema.getResourceFields()).thenReturn(Map.of("publicPort", capability));
            when(fixture.schemas.getSchema("port")).thenReturn(fixture.schema); when(fixture.schemas.getSchema("container")).thenReturn(fixture.schema);
            domain.managers = mock(ResourceManagerLocator.class); when(domain.managers.getResourceManagerByType("port")).thenReturn(manager);
            when(manager.getById(eq("port"), anyString(), any())).thenReturn(data.port);
            when(manager.update(anyString(), anyString(), any())).thenReturn(data.port);
            domain.containerPortUpdates = mock(PortUpdateService.class);
            doReturn(data.snapshot()).when(domain).containerNetworkSnapshot(any(), any(), any(), anyMap(), anyBoolean());
            doAnswer(call -> Long.parseLong(((String) call.getArgument(1)).replaceFirst("^[^0-9]*[0-9][a-z]+", "")))
                    .when(domain).internalId(anyString(), anyString());
            when(domain.objects.loadResource(Account.class, 77L)).thenReturn(data.project);
            when(domain.objects.loadResource(Instance.class, 3L)).thenReturn(data.source);
            when(domain.objects.loadResource(eq("port"), anyLong())).thenReturn(data.port);
            when(domain.objects.find(Port.class, "instanceId", 3L, "removed", null)).thenReturn(data.ports());
        }
    }
}
