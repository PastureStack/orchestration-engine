package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiStackGroupChangeTest {
    private final JacksonJsonMapper json = new JacksonJsonMapper();
    private final AiResourceChangeDescriptor descriptor = AiResourceChangeDescriptor.require("stack", "update_metadata");
    private final Object previousBridge = io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
    @After public void cleanup() {
        io.github.ibuildthecloud.gdapi.context.ApiContext.remove();
        if (previousBridge == null) io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previousBridge);
    }
    private StackRecord stack() {
        StackRecord stack = new StackRecord(); stack.setId(7L); stack.setAccountId(77L); stack.setUuid("stack-uuid");
        stack.setKind("stack"); stack.setSystem(false); stack.setState("upgraded"); stack.setName("ordinary");
        stack.setGroup("Old, Mixed CASE"); stack.setData(new LinkedHashMap<>(Map.of("environment", Map.of("TOKEN", "PRIVATE_DATA"))));
        return stack;
    }
    private AccountRecord project() {
        AccountRecord project = new AccountRecord(); project.setId(77L); project.setUuid("project-uuid"); project.setKind("project");
        return project;
    }
    private Map<String, Object> parameters(Object group) {
        Map<String, Object> parameters = new LinkedHashMap<>(); parameters.put("group", group); return parameters;
    }
    @Test public void groupParametersPreserveNativeTextNullAndEmptyWithoutExtendingOtherWorkflows() {
        for (Object value : new Object[]{"TEAM, Production", "  Blue ,Mixed CASE,,  ", "", null}) {
            Map<String, Object> parameters = descriptor.parameters(parameters(value));
            assertTrue(parameters.containsKey("group")); assertEquals(value, parameters.get("group"));
            assertEquals(parameters, descriptor.body(parameters));
        }
        for (String type : List.of("service", "container", "project", "host", "certificate", "loadBalancerService"))
            assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require(type, "update_metadata").parameters(parameters("Blue")));
        assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("stack", "create")
                .parameters(Map.of("name", "n", "group", "Blue")));
        for (Object value : List.of(List.of("Blue"), 7, true, "x".repeat(256), "bad\0tag"))
            assertThrows(ClientVisibleException.class, () -> descriptor.parameters(parameters(value)));
    }
    @Test public void groupSnapshotBindsNativeColumnIdentityProjectAndConfigurationWithoutCopyingSecrets() throws Exception {
        StackRecord stack = stack(); AccountRecord project = project(); Map<String, Object> before = AiStackGroupChange.snapshot(stack, project, json);
        assertEquals("Old, Mixed CASE", before.get("group")); assertFalse(before.containsKey("data"));
        assertFalse(json.writeValueAsString(before).contains("PRIVATE_DATA"));
        assertEquals(before, json.readValue(json.writeValueAsBytes(before)));
        stack.setGroup("Other"); assertNotEquals(before, AiStackGroupChange.snapshot(stack, project, json)); stack.setGroup("Old, Mixed CASE");
        stack.setUuid("new-uuid"); assertNotEquals(before, AiStackGroupChange.snapshot(stack, project, json)); stack.setUuid("stack-uuid");
        project.setUuid("new-project"); assertNotEquals(before, AiStackGroupChange.snapshot(stack, project, json)); project.setUuid("project-uuid");
        stack.setData(Map.of("environment", Map.of("TOKEN", "NEW_PRIVATE_DATA")));
        assertNotEquals(before, AiStackGroupChange.snapshot(stack, project, json));
        project.setId(78L); assertThrows(ClientVisibleException.class, () -> AiStackGroupChange.snapshot(stack, project, json));
    }
    @Test public void groupAllowsNativeEditableOrdinaryNonemptyStatesButRejectsSystemCatalogAndRemovedStacks() {
        StackRecord stack = stack(); AccountRecord project = project();
        for (String state : List.of("active", "inactive", "upgraded", "upgrading", "error")) {
            stack.setState(state); assertEquals(state, AiStackGroupChange.snapshot(stack, project, json).get("state"));
        }
        stack.setSystem(true); assertThrows(ClientVisibleException.class, () -> AiStackGroupChange.snapshot(stack, project, json)); stack.setSystem(false);
        for (String id : List.of("catalog://library:template:1", "legacy-catalog-id", "kubernetes://managed", " ")) {
            stack.setExternalId(id); assertThrows(ClientVisibleException.class, () -> AiStackGroupChange.snapshot(stack, project, json));
        }
        stack.setExternalId(""); stack.setRemoved(new java.util.Date());
        assertThrows(ClientVisibleException.class, () -> AiStackGroupChange.snapshot(stack, project, json));
    }
    @Test public void groupCompletionRequiresExactRequestedFieldsAndPreservesAllOtherConfiguration() {
        StackRecord stack = stack(); AccountRecord project = project(); Map<String, Object> before = AiStackGroupChange.snapshot(stack, project, json);
        Map<String, Object> parameters = parameters("  Blue, Mixed CASE "); parameters.put("description", "updated");
        stack.setGroup((String) parameters.get("group")); stack.setDescription("updated");
        assertTrue(AiStackGroupChange.complete(stack, parameters, before, project, json));
        stack.setGroup("blue, mixed case"); assertFalse(AiStackGroupChange.complete(stack, parameters, before, project, json));
        stack.setGroup((String) parameters.get("group")); stack.setName("changed elsewhere");
        assertFalse(AiStackGroupChange.complete(stack, parameters, before, project, json)); stack.setName("ordinary");
        stack.setData(Map.of("other", true)); assertFalse(AiStackGroupChange.complete(stack, parameters, before, project, json));
    }
    @Test public void groupStillRequiresNativePutFieldCeilingAndCurrentBoundKeyBeforeDispatch() {
        io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
        AiResourceChangeDomainTest.Fixture f = new AiResourceChangeDomainTest.Fixture();
        when(f.schemas.getSchema("stack")).thenReturn(f.schema); when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
        FieldImpl group = new FieldImpl(); group.setUpdate(true); group.setIncludeInList(true);
        when(f.schema.getResourceFields()).thenReturn(Map.of("group", group));
        Map<String, Object> parameters = descriptor.parameters(parameters(" Exact, Tags "));
        Map<String, Object> bound = Map.of("credentialId", 7);
        assertEquals(parameters, f.domain.prepare(f.request, descriptor, "1st7", parameters, bound));
        verify(f.domain.keys).authorizeBoundDomain(eq(bound), argThat(r -> "stack".equals(r.getType()) && "PUT".equals(r.getMethod())));
        group.setUpdate(false); assertThrows(ClientVisibleException.class, () -> f.domain.prepare(f.request, descriptor, "1st7", parameters, bound));
        group.setUpdate(true); when(f.schema.getResourceMethods()).thenReturn(List.of("GET"));
        assertThrows(ClientVisibleException.class, () -> f.domain.prepare(f.request, descriptor, "1st7", parameters, bound));
        when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
        doThrow(new ClientVisibleException(403, "KeyPolicyDenied")).when(f.domain.keys).authorizeBoundDomain(anyMap(), any());
        assertThrows(ClientVisibleException.class, () -> f.domain.prepare(f.request, descriptor, "1st7", parameters, bound));
    }
    @Test public void groupSnapshotAndTerminalReceiptRequireCurrentReadableFieldRoleAndExactIdentity() {
        AiResourceChangeDomainTest.Fixture f = new AiResourceChangeDomainTest.Fixture(); StackRecord stack = stack();
        AccountRecord project = project(); project.setState("active"); when(f.domain.objects.loadResource(Account.class, 77L)).thenReturn(project);
        when(f.schemas.getSchema("stack")).thenReturn(f.schema); when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
        FieldImpl group = new FieldImpl(); group.setIncludeInList(true); when(f.schema.getResourceFields()).thenReturn(Map.of("group", group));
        Map<String, Object> identity = AiStackGroupChange.identity(f.domain.stackGroupSnapshot(f.request, stack));
        Map<String, Object> plan = Map.of("internalResourceId", 7L, "resourceId", "1st7", "parameters", parameters("Blue"),
                "status", "succeeded", "stackGroupIdentity", identity);
        when(f.domain.objects.loadResource("stack", 7L)).thenReturn(stack); when(f.policy.authorizeObject(stack)).thenReturn(stack);
        AiResourceChangeDomain domain = spy(f.domain); doReturn(stack).when(domain).visible(any(), any(), eq("1st7"));
        assertSame(stack, domain.receiptTarget(f.request, descriptor, plan));
        stack.setUuid("replaced"); assertThrows(ClientVisibleException.class, () -> domain.receiptTarget(f.request, descriptor, plan));
        stack.setUuid("stack-uuid"); project.setUuid("replaced-project");
        assertThrows(ClientVisibleException.class, () -> domain.receiptTarget(f.request, descriptor, plan)); project.setUuid("project-uuid");
        when(f.policy.authorizeObject(stack)).thenReturn(null);
        assertThrows(ClientVisibleException.class, () -> domain.receiptTarget(f.request, descriptor, plan));
        when(f.policy.authorizeObject(stack)).thenReturn(stack); group.setReadOnCreateOnly(true);
        assertThrows(ClientVisibleException.class, () -> domain.receiptTarget(f.request, descriptor, plan));
        group.setReadOnCreateOnly(false);
        doThrow(new ClientVisibleException(403, "KeyPolicyDenied")).when(domain).visible(any(), any(), eq("1st7"));
        assertThrows(ClientVisibleException.class, () -> domain.receiptTarget(f.request, descriptor, plan));
    }
    @Test public void groupPlanRetainsHumanConfirmationAndDetectsGroupAndParentUuidDriftBeforeDispatch() {
        AiResourceChangePlanServiceTest.Fixture f = new AiResourceChangePlanServiceTest.Fixture();
        StackRecord stack = stack(); AccountRecord project = project();
        when(f.domain.lock(eq("stack"), anyLong())).thenReturn(stack); when(f.domain.visible(any(), any(), eq("1st7"))).thenReturn(stack);
        when(f.domain.receiptTarget(any(), any(), anyMap())).thenReturn(stack);
        when(f.domain.stackGroupSnapshot(any(), any())).thenAnswer(call -> AiStackGroupChange.snapshot(call.getArgument(1), project, json));
        when(f.domain.changes(any(), any(), anyMap())).thenReturn(List.of(Map.of("field", "group", "before", stack.getGroup(), "after", "Blue")));
        var preview = f.request(null, true); preview.setId(null);
        preview.setRequestObject(Map.of("resourceType", "stack", "operation", "update_metadata", "resourceId", "1st7", "parameters", parameters("Blue")));
        Map<String, Object> result = f.service.preview(preview); f.id = (String) result.get("id");
        assertFalse(result.containsKey("snapshot")); assertFalse(result.toString().contains("PRIVATE_DATA"));
        assertThrows(ClientVisibleException.class, () -> f.apply(true)); assertEquals(0, f.dispatched);
        stack.setGroup("native changed");
        assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class, f::confirm).getCode()); assertEquals(0, f.claims);
        stack.setGroup("Old, Mixed CASE"); f.confirm(); project.setUuid("other-project");
        assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class, () -> f.apply(true)).getCode()); assertEquals(0, f.dispatched);
    }
}
