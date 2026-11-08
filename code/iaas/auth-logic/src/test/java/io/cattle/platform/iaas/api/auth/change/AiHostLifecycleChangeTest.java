package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiHostLifecycleChangeTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void onlyEmptyHostStartAndStopMapToNativeSchedulingActions() {
        for (String operation : List.of("start", "stop")) {
            var d = AiResourceChangeDescriptor.require("host", operation);
            assertEquals("POST", d.method()); assertEquals("update", d.keyOperation());
            assertEquals(operation.equals("start") ? "activate" : "deactivate", d.action());
            assertEquals(Map.of(), d.parameters(Map.of())); assertEquals(Map.of(), d.body(Map.of()));
            for (String field : List.of("name", "description", "resetName", "state", "hostname", "labels", "driver", "evacuate", "data"))
                assertThrows(ClientVisibleException.class, () -> d.parameters(Map.of(field, "PRIVATE")));
        }
        for (String operation : List.of("remove", "create", "restart", "evacuate", "purge"))
            assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("host", operation));
        assertEquals("PUT", AiResourceChangeDescriptor.require("host", "update_metadata").method());
    }

    @Test public void hostReadyAndTerminalAreIdentityAndConfigurationBoundWithoutStackChildrenOrPrivatePayload() {
        var domain = new AiResourceChangeDomain(); domain.json = new JacksonJsonMapper(); domain.objects = mock(ObjectManager.class);
        var project = project(); when(domain.objects.loadResource(Account.class, 5L)).thenReturn(project);
        for (String operation : List.of("start", "stop")) {
            var host = host(); host.setState(operation.equals("start") ? "inactive" : "active");
            var d = AiResourceChangeDescriptor.require("host", operation); domain.ready(d, host, null);
            var before = domain.hostLifecycleSnapshot(host);
            assertFalse(before.toString().contains("PRIVATE")); assertFalse(before.containsKey("data"));
            assertFalse(domain.snapshot(host).containsKey("configurationSha256")); // Original metadata snapshot stays unchanged.
            host.setState(operation.equals("start") ? "active" : "inactive");
            assertTrue(domain.complete(d, host, Map.of(), before));
            assertFalse(domain.complete(d, host, Map.of("name", "mixed"), before));
            assertThrows(ClientVisibleException.class, () -> domain.ready(d, host, null));
            host.setUuid("replacement"); assertFalse(domain.complete(d, host, Map.of(), before)); host.setUuid("host-uuid");
            var original = host.getData(); host.setData(Map.of("fields", Map.of("labels", Map.of("changed", "yes"))));
            assertFalse(domain.complete(d, host, Map.of(), before)); host.setData(original);
            project.setUuid("replacement"); assertFalse(domain.complete(d, host, Map.of(), before)); project.setUuid("project-uuid");
            host.setRemoved(new java.util.Date()); assertFalse(domain.complete(d, host, Map.of(), before));
        }
        verify(domain.objects, never()).find(eq(io.cattle.platform.core.model.Service.class), eq("stackId"), any(), eq("removed"), any());
    }

    @Test public void onlyNativeReadOnlyObservationsMayChangeAndUnknownConfigurationRemainsPrivateCas() {
        var host = host(); var project = project(); var json = new JacksonJsonMapper();
        var before = AiHostLifecycleChange.snapshot(host, project, json);
        host.setData(Map.of("fields", Map.of("labels", Map.of("role", "test"), "secret", "PRIVATE",
                "info", Map.of("memory", 222), "agentIpAddress", "192.0.2.2")));
        assertEquals(before, AiHostLifecycleChange.snapshot(host, project, json));
        host.setData(Map.of("fields", Map.of("labels", Map.of("role", "test"), "secret", "CHANGED")));
        assertNotEquals(before, AiHostLifecycleChange.snapshot(host, project, json));
    }

    @Test public void hostConfirmAndApplyCasRejectUuidStateAndConfigurationChangesBeforeAnyDispatch() {
        for (String operation : List.of("start", "stop")) for (String field : List.of("uuid", "state", "configurationSha256", "projectUuid")) {
            var f = new AiResourceChangePlanServiceTest.Fixture();
            var snapshot = new LinkedHashMap<String, Object>(Map.of("id", 7, "uuid", "host-uuid", "accountId", 77,
                    "kind", "docker", "state", operation.equals("start") ? "inactive" : "active", "projectUuid", "project-uuid",
                    "configurationSha256", "original"));
            when(f.domain.hostLifecycleSnapshot(any())).thenAnswer(call -> f.copy(snapshot));
            preview(f, operation); Object original = snapshot.put(field, "replacement");
            assertThrows(ClientVisibleException.class, f::confirm); assertEquals(0, f.claims);
            snapshot.put(field, original); f.confirm(); snapshot.put(field, "replacement");
            assertThrows(ClientVisibleException.class, () -> f.apply(true)); assertEquals(0, f.claims); assertEquals(0, f.dispatched);
        }
    }

    @Test public void humanConfirmationCancelAndLiveBoundKeyDenialsRetainZeroDispatch() {
        for (String operation : List.of("start", "stop")) {
            var f = new AiResourceChangePlanServiceTest.Fixture();
            when(f.domain.hostLifecycleSnapshot(any())).thenReturn(Map.of("uuid", "host-uuid")); preview(f, operation);
            assertThrows(ClientVisibleException.class, () -> f.service.action(f.id, f.request("confirm", true)));
            f.confirm(); var changed = f.request("apply", true);
            ApiKeyCredentialContext.attach(changed, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 4, null));
            assertThrows(ClientVisibleException.class, () -> f.service.action(f.id, changed));
            f.writeAllowed = false; assertThrows(ClientVisibleException.class, () -> f.apply(true));
            assertEquals(0, f.claims); assertEquals(0, f.dispatched);
            f.service.action(f.id, f.request("cancel", false)); assertEquals("cancelled", f.documents.get(f.id).get("status"));
            assertEquals(0, f.dispatched);
        }
    }

    @Test public void freshNativeRoleAndKeyCeilingAndExactReadIdRemainAuthoritativeBeforePlanCreation() throws Exception {
        var owner = mock(Policy.class); var host = host(); var project = project();
        when(owner.getAuthenticatedAsAccountId()).thenReturn(10L); when(owner.getAccountId()).thenReturn(5L);
        var domain = new AiResourceChangeDomain() {
            @Override public Policy actor(io.github.ibuildthecloud.gdapi.request.ApiRequest request) { return owner; }
            @Override public long internalId(String type, String id) { return Long.parseLong(id.substring(2)); }
        };
        domain.json = new JacksonJsonMapper(); domain.objects = mock(ObjectManager.class);
        when(domain.objects.loadResource(Account.class, 5L)).thenReturn(project);
        domain.managers = mock(io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator.class);
        var manager = mock(io.github.ibuildthecloud.gdapi.request.resource.ResourceManager.class);
        when(domain.managers.getResourceManagerByType("host")).thenReturn(manager);
        when(manager.getById(eq("host"), anyString(), any())).thenReturn(host);
        var schema = mock(io.github.ibuildthecloud.gdapi.model.Schema.class);
        var schemas = mock(io.github.ibuildthecloud.gdapi.factory.SchemaFactory.class);
        when(schemas.getSchema("host")).thenReturn(schema); when(schema.getResourceMethods()).thenReturn(List.of("GET"));
        when(schema.getResourceActions()).thenReturn(Map.of());
        domain.authenticator = mock(io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.class);
        when(domain.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(
                new io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator.CurrentAuthorization(owner, schemas));
        domain.validation = mock(io.github.ibuildthecloud.gdapi.validation.ValidationHandler.class);
        domain.keyAccess = mock(io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService.class);
        var request = new io.github.ibuildthecloud.gdapi.request.ApiRequest(null, schemas);
        ApiContext.newContext().setPolicy(owner);
        var descriptor = AiResourceChangeDescriptor.require("host", "start");
        assertSame(host, domain.visible(request, descriptor, "1h3"));
        assertThrows(ClientVisibleException.class, () -> domain.visible(request, descriptor, "1h4"));
        assertThrows(ClientVisibleException.class, () -> domain.prepare(request, descriptor, "1h3", Map.of()));
        when(schema.getResourceActions()).thenReturn(Map.of("activate", mock(io.github.ibuildthecloud.gdapi.model.Action.class)));
        assertEquals(Map.of(), domain.prepare(request, descriptor, "1h3", Map.of()));
        doThrow(new ClientVisibleException(403, "KeyPolicyDenied")).when(domain.keyAccess).authorize(any(), eq(owner));
        assertThrows(ClientVisibleException.class, () -> domain.prepare(request, descriptor, "1h3", Map.of()));
        reset(domain.keyAccess);
        doAnswer(call -> { ((io.github.ibuildthecloud.gdapi.request.ApiRequest) call.getArgument(0)).setRequestObject(Map.of("name", "mixed")); return null; })
                .when(domain.validation).generate(any());
        assertThrows(ClientVisibleException.class, () -> domain.prepare(request, descriptor, "1h3", Map.of()));
        verify(manager, never()).resourceAction(anyString(), any());
    }

    @Test public void matchingHostStateCannotQualifyUntilItsNativeProcessOutcomeSucceeds() {
        var f = new AiResourceChangePlanServiceTest.Fixture();
        when(f.domain.hostLifecycleSnapshot(any())).thenReturn(Map.of("id", 7, "uuid", "host-uuid", "accountId", 77,
                "kind", "docker", "state", "inactive", "projectUuid", "project-uuid", "configurationSha256", "original"));
        when(f.domain.hostLifecycleProcessOutcome(anyMap(), any())).thenReturn("running");
        preview(f, "start"); f.confirm(); f.apply(true); f.complete = true;
        var pending = f.service.get(f.id, f.request(null, true));
        assertEquals("running", pending.get("status")); assertEquals(false, pending.get("verifiedResult"));
        when(f.domain.hostLifecycleProcessOutcome(anyMap(), any())).thenReturn("succeeded");
        var terminal = f.service.get(f.id, f.request(null, true));
        assertEquals("succeeded", terminal.get("status")); assertEquals(true, terminal.get("verifiedResult"));
        f.apply(true); assertEquals(1, f.claims); assertEquals(1, f.dispatched);
    }

    static HostRecord host() {
        var h = new HostRecord(); h.setId(3L); h.setUuid("host-uuid"); h.setAccountId(5L); h.setKind("docker"); h.setState("inactive");
        h.setName("same-name"); h.setData(Map.of("fields", Map.of("labels", Map.of("role", "test"), "secret", "PRIVATE", "info", Map.of("memory", 111))));
        return h;
    }
    static AccountRecord project() {
        var p = new AccountRecord(); p.setId(5L); p.setUuid("project-uuid"); p.setKind("project"); p.setState("active"); return p;
    }
    private void preview(AiResourceChangePlanServiceTest.Fixture f, String operation) {
        var request = f.request(null, true); request.setId(null);
        request.setRequestObject(Map.of("resourceType", "host", "operation", operation, "resourceId", "1h7", "parameters", Map.of()));
        f.id = (String) f.service.preview(request).get("id");
    }
}
