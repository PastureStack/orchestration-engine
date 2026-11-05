package io.cattle.platform.iaas.api.auth.ai.view;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.engine.handler.ProcessHandler;
import io.cattle.platform.engine.manager.ProcessManager;
import io.cattle.platform.engine.process.ProcessDefinition;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

public class AiProcessDiagnosticsTest {
    static final String SECRET = "PRIVATE_PAYLOAD_SENTINEL";
    private final Object originalBridgeFlag = io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
    @After public void cleanup() {
        ApiContext.remove();
        if (originalBridgeFlag == null) io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", originalBridgeFlag);
    }

    @Test public void registeredNamesAndClosedEnumsReplaceAllPersistedLabelsAndPayloadText() {
        Fixture f = new Fixture();
        Map<String, Object> fields = new LinkedHashMap<>(parent("12").getFields());
        fields.put("name", SECRET); fields.put("description", SECRET); fields.put("result", SECRET);
        fields.put("data", Map.of("token", SECRET)); fields.put("phase", "HANDLERS"); fields.put("exitReason", "TIMEOUT");
        Map<String, Object> safe = f.projection.project("12", fields);
        assertEquals("service.create", safe.get("name")); assertEquals("service.create", safe.get("processName"));
        assertEquals("HANDLERS", safe.get("phase")); assertEquals("TIMEOUT", safe.get("exitReason"));
        assertEquals("1970-01-01T00:00:01Z", safe.get("startTime")); assertTrue(safe.containsKey("endTime"));
        assertFalse(safe.toString().contains(SECRET)); assertFalse(safe.containsKey("resourceId"));
        fields.put("processName", SECRET); fields.put("phase", SECRET); fields.put("exitReason", SECRET);
        fields.put("executionCount", -1); fields.put("runAfter", SECRET);
        safe = f.projection.project("12", fields);
        assertEquals("unknown", safe.get("name")); assertFalse(safe.containsKey("processName"));
        for (String field : List.of("phase", "exitReason", "runAfter", "executionCount")) assertFalse(safe.containsKey(field));
    }

    @Test public void onlyRegisteredRootHandlersAreProjectedAndEmptyOrOversizedLogsStayUnknown() {
        Fixture f = new Fixture();
        Map<String, Object> safe = f.projection.diagnose(parent("12").getFields(), List.of(log("CreateHandler")), false);
        assertEquals("available", safe.get("diagnosticStatus")); assertEquals("unknown", safe.get("diagnosticCompleteness"));
        assertEquals("CreateHandler", safe.get("failedHandler")); assertEquals(true, safe.get("hasException"));
        assertFalse(safe.toString().contains(SECRET));
        safe = f.projection.diagnose(parent("12").getFields(), List.of(log(SECRET)), false);
        assertFalse(safe.containsKey("failedHandler")); assertFalse(safe.toString().contains(SECRET));
        for (List<Map<String, Object>> logs : List.<List<Map<String, Object>>>of(List.of(), List.of(Map.<String, Object>of("executions", List.of())),
                java.util.Collections.nCopies(51, log("CreateHandler"))))
            assertEquals("unknown", f.projection.diagnose(parent("12").getFields(), logs, false).get("diagnosticStatus"));
        assertEquals("unknown", f.projection.diagnose(parent("12").getFields(), List.of(log("CreateHandler")), true).get("diagnosticStatus"));
        List<Object> huge = new java.util.AbstractList<>() {
            @Override public int size() { return 1000000; }
            @Override public Object get(int index) { throw new AssertionError("Oversized logs must not be traversed"); }
        };
        assertEquals("unknown", f.projection.diagnose(parent("12").getFields(),
                List.of(Map.of("executions", huge)), false).get("diagnosticStatus"));
        assertEquals("unknown", f.projection.diagnose(parent("12").getFields(), List.of(Map.of("executions",
                List.of(Map.of("processName", "service.create", "processHandlerExecutions", huge)))), false).get("diagnosticStatus"));
    }

    @Test public void freshGlobalSchemaAndKeyDecisionsPrecedeNativeReadsWithoutProjectRoleFallback() {
        Fixture f = new Fixture(); f.base.rows.add(parent("12"));
        Map<?, ?> result = f.base.service.read(f.base.request(Map.of("resourceType", "processInstance")));
        assertEquals(1, ((List<?>) result.get("data")).size());
        verify(f.base.authenticator).currentAuthorization(eq(42L), eq(42L), any());
        verify(f.base.keyAccess).authorize(argThat(r -> "processInstance".equals(r.getType()) && "GET".equals(r.getMethod())), eq(f.base.live));
        clearInvocations(f.base.manager, f.base.keyAccess);
        when(f.base.schema.getCollectionMethods()).thenReturn(List.of());
        assertEquals("OwnerPermissionDenied", rejected(() -> f.base.service.read(f.base.request(Map.of("resourceType", "processInstance")))));
        verifyNoInteractions(f.base.manager, f.base.keyAccess);
        when(f.base.schema.getCollectionMethods()).thenReturn(List.of("GET"));
        doThrow(new ClientVisibleException(403, "KeyScopeDenied")).when(f.base.keyAccess).authorize(any(), any());
        assertEquals("KeyScopeDenied", rejected(() -> f.base.service.read(f.base.request(Map.of("resourceType", "processInstance")))));
        verifyNoInteractions(f.base.manager);
    }

    @Test public void nativeListsRetainFiftyRowCursorAndOnlyEscapedProcessNameConditions() {
        Fixture f = new Fixture(); for (int i = 1; i <= 51; i++) f.base.rows.add(parent(Integer.toString(i)));
        Map<String, Object> result = f.base.service.read(f.base.request(Map.of("resourceType", "processInstance", "query", "service")));
        assertEquals(50, ((List<?>) result.get("data")).size()); assertEquals("50", result.get("nextMarker"));
        verify(f.base.manager).list(eq("processInstance"), argThat((ApiRequest r) -> r.getPagination().getLimit() == 51
                && r.getConditions().containsKey("processName") && !r.getConditions().containsKey("name")));
        assertFalse(result.toString().contains(SECRET));
        assertEquals("ResourceViewInputInvalid", rejected(() -> f.base.service.read(f.base.request(Map.of(
                "resourceType", "processInstance", "resourceId", "12", "query", "service")))));
    }

    @Test public void boundedChildReadsRequireFreshParentAndExactRelationshipAndDoNotExportChildren() {
        Fixture f = new Fixture(); f.fullGuard = true;
        io.cattle.platform.archaius.util.ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
        doAnswer(call -> {
            io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard.check(call.getArgument(0), f.fullPolicy);
            return null;
        }).when(f.base.keyAccess).authorize(any(), any());
        f.childRows.add(child("91", "12"));
        Map<String, Object> result = f.base.service.read(f.request(Map.of("resourceType", "processInstance", "resourceId", "12")));
        Map<?, ?> detail = (Map<?, ?>) ((List<?>) result.get("data")).getFirst();
        assertEquals("CreateHandler", detail.get("failedHandler")); assertEquals("unknown", detail.get("diagnosticCompleteness"));
        assertFalse(result.toString().contains(SECRET)); assertFalse(result.toString().contains("91"));
        verify(f.base.manager).list(eq("processExecution"), argThat((ApiRequest r) -> r.getPagination().getLimit() == 51
                && r.getConditions().size() == 1 && r.getConditions().containsKey("processInstanceId") && r.getLink() == null));
        verify(f.base.manager, times(2)).getById(eq("processInstance"), eq("12"), any());
        f.childRows.clear(); f.childRows.add(child("92", "13"));
        assertEquals("ResourceViewUnavailable", rejected(() -> f.base.service.read(f.request(Map.of(
                "resourceType", "processInstance", "resourceId", "12")))));
        doAnswer(call -> {
            ApiRequest domain = call.getArgument(0);
            if ("processExecution".equals(domain.getType()))
                when(domain.getServletContext().getRequest().getHeader(
                        io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("2");
            io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard.check(domain, f.fullPolicy);
            return null;
        }).when(f.base.keyAccess).authorize(any(), any());
        assertEquals("ApiKeyPolicyChanged", rejected(() -> f.base.service.read(f.request(Map.of(
                "resourceType", "processInstance", "resourceId", "12")))));
        assertSame(f.base.originalContext, ApiContext.getContext().getApiRequest());
        assertSame(f.base.owner, ApiContext.getContext().getPolicy());
    }

    @Test public void hiddenChildFieldsDeniedKeysAndChangingParentFailClosedWithoutCountsOrWrites() {
        Fixture f = new Fixture();
        when(f.field.isIncludeInList()).thenReturn(false);
        Map<?, ?> detail = first(f.base.service.read(f.base.request(Map.of("resourceType", "processInstance", "resourceId", "12"))));
        assertEquals("unknown", detail.get("diagnosticStatus")); verify(f.base.manager, never()).list(eq("processExecution"), any(ApiRequest.class));
        when(f.field.isIncludeInList()).thenReturn(true);
        doAnswer(call -> {
            if ("processExecution".equals(((ApiRequest) call.getArgument(0)).getType())) throw new ClientVisibleException(403, "KeyScopeDenied");
            return null;
        }).when(f.base.keyAccess).authorize(any(), any());
        detail = first(f.base.service.read(f.base.request(Map.of("resourceType", "processInstance", "resourceId", "12"))));
        assertEquals("unknown", detail.get("diagnosticCompleteness")); assertFalse(detail.containsKey("count"));
        when(f.base.manager.getById(eq("processInstance"), eq("12"), any())).thenReturn(parent("12"), parent("13"));
        assertEquals("ResourceViewUnavailable", rejected(() -> f.base.service.read(f.base.request(Map.of(
                "resourceType", "processInstance", "resourceId", "12")))));
        verify(f.base.manager, never()).resourceAction(anyString(), any());
        assertTrue(mockingDetails(f.base.manager).getInvocations().stream().allMatch(call ->
                List.of("getById", "convertResponse", "list").contains(call.getMethod().getName())));
    }

    @Test public void globalEntryBudgetTruncatesAcrossRootsWithoutRejectingSmallOrOneHundredHandlerLogs() {
        Fixture f = new Fixture();
        Map<String, Object> result = f.projection.diagnose(parent("12").getFields(), List.of(budgetLog(3, 85, "CreateHandler")), false);
        assertEquals("unknown", result.get("diagnosticStatus")); assertEquals(true, result.get("diagnosticTruncated"));
        assertEquals("entry_budget", result.get("diagnosticTruncationReason")); assertFalse(result.containsKey("hasException"));
        assertFalse(result.containsKey("failedHandler")); assertFalse(result.toString().contains(SECRET));
        assertEquals("available", f.projection.diagnose(parent("12").getFields(), List.of(log("CreateHandler")), false).get("diagnosticStatus"));
        String longest = "H".repeat(128); ProcessHandler handler = mock(ProcessHandler.class); when(handler.getName()).thenReturn(longest);
        when(f.definition.getProcessHandlers()).thenReturn(List.of(handler));
        result = f.projection.diagnose(parent("12").getFields(), List.of(budgetLog(1, 100, longest)), false);
        assertEquals("available", result.get("diagnosticStatus")); assertEquals(longest, result.get("failedHandler"));
        assertFalse(result.containsKey("diagnosticTruncated")); assertEquals("unknown", result.get("diagnosticCompleteness"));
    }

    @Test public void globalUtf8BudgetChargesBytesAndReturnsOnlyFixedTruncationMetadata() throws Exception {
        Fixture f = new Fixture(); String longest = "H".repeat(128);
        ProcessHandler handler = mock(ProcessHandler.class); when(handler.getName()).thenReturn(longest);
        when(f.definition.getProcessHandlers()).thenReturn(List.of(handler));
        Map<String, Object> result = f.projection.diagnose(parent("12").getFields(), List.of(budgetLog(2, 100, longest)), false);
        assertEquals("utf8_budget", result.get("diagnosticTruncationReason")); assertEquals(true, result.get("diagnosticTruncated"));
        assertEquals("unknown", result.get("diagnosticStatus")); assertEquals("unknown", result.get("diagnosticCompleteness"));
        assertFalse(result.containsKey("failedHandler")); assertFalse(result.containsKey("hasException"));
        String wire = new io.cattle.platform.json.JacksonJsonMapper().writeValueAsString(result);
        assertTrue(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 1024);
        assertFalse(wire.contains(SECRET)); assertFalse(wire.contains(longest));
        AiProcessDiagnostics.Budget budget = new AiProcessDiagnostics.Budget(); int before = budget.utf8Bytes;
        assertTrue(budget.text("界")); assertEquals(before + 3, budget.utf8Bytes);
    }

    @Test public void nativeChildLimitReportsSourceTruncationWithoutParsingRowsOrRetainingPartialConclusions() {
        Fixture f = new Fixture(); f.childRows.addAll(java.util.Collections.nCopies(51, child("91", "12")));
        Map<?, ?> detail = first(f.base.service.read(f.base.request(Map.of("resourceType", "processInstance", "resourceId", "12"))));
        assertEquals(true, detail.get("diagnosticTruncated")); assertEquals("source_limit", detail.get("diagnosticTruncationReason"));
        assertEquals("unknown", detail.get("diagnosticStatus")); assertEquals("unknown", detail.get("diagnosticCompleteness"));
        assertFalse(detail.containsKey("hasException")); assertFalse(detail.containsKey("failedHandler"));
        assertFalse(detail.toString().contains(SECRET)); assertFalse(detail.containsKey("count"));
        verify(f.base.manager).list(eq("processExecution"), argThat((ApiRequest r) -> r.getPagination().getLimit() == 51
                && r.getConditions().size() == 1 && r.getConditions().containsKey("processInstanceId")));
        verify(f.base.manager, never()).convertResponse(argThat((Object row) -> row instanceof Resource resource
                && "processExecution".equals(resource.getType())), any());
        f.childRows.clear();
        detail = first(f.base.service.read(f.base.request(Map.of("resourceType", "processInstance", "resourceId", "12"))));
        assertEquals("unknown", detail.get("diagnosticStatus")); assertFalse(detail.containsKey("diagnosticTruncated"));
        clearInvocations(f.base.manager); when(f.field.isIncludeInList()).thenReturn(false);
        detail = first(f.base.service.read(f.base.request(Map.of("resourceType", "processInstance", "resourceId", "12"))));
        assertEquals("unknown", detail.get("diagnosticCompleteness")); assertFalse(detail.containsKey("diagnosticTruncated"));
        verify(f.base.manager, never()).list(eq("processExecution"), any(ApiRequest.class));
    }

    private static Map<?, ?> first(Map<String, Object> result) { return (Map<?, ?>) ((List<?>) result.get("data")).getFirst(); }
    private static Resource parent(String id) {
        Map<String, Object> fields = new LinkedHashMap<>(); fields.put("processName", "service.create");
        fields.put("resourceType", "service"); fields.put("resourceId", "PRIVATE_TARGET"); fields.put("startTime", new Date(1000));
        fields.put("endTime", null); fields.put("executionCount", 2); fields.put("data", SECRET);
        return new ResourceImpl(id, "processInstance", fields);
    }
    private static Map<String, Object> log(String handler) {
        return Map.of("executions", List.of(Map.of("processName", "service.create", "message", SECRET,
                "children", List.of(Map.of("name", SECRET)), "processHandlerExecutions", List.of(Map.of("name", handler,
                        "exception", Map.of("message", SECRET, "cause", SECRET, "stackTrace", SECRET))))));
    }
    private static Map<String, Object> budgetLog(int roots, int handlers, String name) {
        Map<String, Object> handler = Map.of("name", name, "exception", Map.of("message", SECRET, "stackTrace", SECRET));
        Map<String, Object> root = Map.of("processName", "service.create", "processHandlerExecutions",
                java.util.Collections.nCopies(handlers, handler), "children", Map.of("data", SECRET));
        return Map.of("executions", java.util.Collections.nCopies(roots, root));
    }
    private static Resource child(String id, String parent) {
        return new ResourceImpl(id, "processExecution", Map.of("processInstanceId", parent, "log", log("CreateHandler")));
    }
    private static String rejected(Runnable operation) {
        try { operation.run(); fail("must reject"); return null; }
        catch (ClientVisibleException failure) { return failure.getCode(); }
    }
    static class Fixture {
        final AiResourceViewServiceTest.Fixture base = new AiResourceViewServiceTest.Fixture();
        final AiProcessDiagnostics projection = new AiProcessDiagnostics();
        final ProcessDefinition definition = mock(ProcessDefinition.class);
        final Field field = mock(Field.class);
        final List<Resource> childRows = new ArrayList<>();
        boolean fullGuard;
        final io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy fullPolicy = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy(
                io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Mode.FULL,
                io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Effect.ALLOW, null, List.of(),
                io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Purpose.AI_BRIDGE);
        ApiRequest request(Map<String, Object> body) {
            ApiRequest request = base.request(body);
            if (fullGuard) {
                var http = mock(jakarta.servlet.http.HttpServletRequest.class);
                when(http.getHeader(io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
                request.setRequestServletContext(new io.github.ibuildthecloud.gdapi.server.model.ApiServletContext(http, null, null));
                io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext.attach(request,
                        new io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext(5, 42, "apiKeyRestricted", 3, fullPolicy));
            }
            return request;
        }
        Fixture() {
            projection.processes = mock(ProcessManager.class); when(projection.processes.getProcessDefinition("service.create")).thenReturn(definition);
            when(definition.getName()).thenReturn("service.create"); ProcessHandler handler = mock(ProcessHandler.class);
            when(handler.getName()).thenReturn("CreateHandler"); when(definition.getProcessHandlers()).thenReturn(List.of(handler));
            when(definition.getPreProcessListeners()).thenReturn(List.of()); when(definition.getPostProcessListeners()).thenReturn(List.of());
            base.service.processDiagnostics = projection;
            when(field.isIncludeInList()).thenReturn(true);
            when(base.schema.getResourceFields()).thenReturn(Map.of("processInstanceId", field, "log", field));
            when(base.manager.getById(eq("processInstance"), eq("12"), any())).thenAnswer(call -> parent("12"));
            when(base.manager.list(anyString(), any(ApiRequest.class))).thenAnswer(call ->
                    "processExecution".equals(call.getArgument(0)) ? childRows : base.rows);
        }
    }
}
