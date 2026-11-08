package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.HostTable.HOST;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicyEvaluator;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.sql.DriverManager;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.junit.After;
import org.junit.Test;

/** New host subset through the existing domain and durable plan owners. */
public class AiHostMetadataChangeTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void onlyMetadataIntentAndFieldsAreAccepted() {
        var descriptor = descriptor();
        assertEquals("PUT", descriptor.method()); assertNull(descriptor.action()); assertEquals("update", descriptor.keyOperation());
        Map<String, Object> metadata = new LinkedHashMap<>(); metadata.put("name", "new-name"); metadata.put("description", null);
        assertEquals(metadata, descriptor.body(descriptor.parameters(metadata)));
        for (String field : List.of("hostname", "labels", "info", "credentials", "data", "accountId", "uuid", "state", "memory", "milliCpu"))
            assertEquals("ResourceChangeInputInvalid", assertThrows(ClientVisibleException.class,
                    () -> descriptor.parameters(Map.of(field, "PRIVATE-CANARY"))).getCode());
        for (String operation : List.of("create", "remove", "restore", "update_targets"))
            assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("host", operation));
        for (String operation : List.of("start", "stop")) {
            var lifecycle = AiResourceChangeDescriptor.require("host", operation);
            assertFalse(AiHostMetadataChange.supports(lifecycle)); assertTrue(AiHostLifecycleChange.supports(lifecycle));
            assertEquals("POST", lifecycle.method()); assertEquals(operation.equals("start") ? "activate" : "deactivate", lifecycle.action());
            assertEquals(Map.of(), lifecycle.body(lifecycle.parameters(Map.of())));
            assertThrows(ClientVisibleException.class, () -> lifecycle.parameters(Map.of("name", "PRIVATE-CANARY")));
            assertThrows(ClientVisibleException.class, () -> lifecycle.parameters(Map.of("description", "PRIVATE-CANARY")));
        }
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of()));
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of("name", " ")));
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of("description", "x".repeat(1025))));
    }

    @Test public void nativePutUsesRealRevisionGuardAndRestoresContext() throws Exception {
        var f = fixture(); var manager = f.domain.managers.getResourceManagerByType("host");
        var target = host(); Map<String, Object> body = Map.of("name", "safe-name");
        when(manager.update(eq("host"), eq("1h7"), any())).thenAnswer(call -> {
            ApiRequest actual = call.getArgument(2); assertEquals(body, actual.getRequestObject());
            assertEquals("PUT", actual.getMethod()); assertNull(actual.getAction());
            assertTrue(AiResourceChangeDomain.trustedDomain(actual));
            assertSame(actual, ApiContext.getContext().getApiRequest()); return target;
        });
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            assertEquals(body, f.domain.prepare(f.request, descriptor(), "1h7", body));
            assertSame(target, f.domain.dispatch(f.request, descriptor(), "1h7", body, "own-plan"));
            assertSame(f.request, ApiContext.getContext().getApiRequest()); assertSame(f.policy, ApiContext.getContext().getPolicy());
            when(f.http.getHeader(ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("2");
            assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class,
                    () -> f.domain.dispatch(f.request, descriptor(), "1h7", body, "own-plan")).getCode());
            verify(manager, times(1)).update(eq("host"), eq("1h7"), any());
            assertSame(f.request, ApiContext.getContext().getApiRequest());
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void nativeMethodFieldAndCrossProjectCeilingsRejectBeforeMutation() throws Exception {
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            var f = fixture(); var manager = f.domain.managers.getResourceManagerByType("host");
            when(f.schema.getResourceMethods()).thenReturn(List.of("GET"));
            assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor(), "1h7", Map.of("name", "safe"))).getCode());
            when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT")); f.name.setUpdate(false);
            assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor(), "1h7", Map.of("name", "safe"))).getCode());
            verify(f.validation, never()).generate(any()); f.name.setUpdate(true);
            var row = host(); when(manager.getById(eq("host"), eq("1h7"), any())).thenReturn(row);
            row.setAccountId(88L);
            assertEquals("NotFound", assertThrows(ClientVisibleException.class,
                    () -> f.domain.visible(f.request, descriptor(), "1h7")).getCode());
            row.setAccountId(77L);
            var wrongModel = new ServiceRecord(); wrongModel.setId(7L); wrongModel.setAccountId(77L); wrongModel.setKind("host");
            when(manager.getById(eq("host"), eq("1h7"), any())).thenReturn(wrongModel);
            assertEquals("NotFound", assertThrows(ClientVisibleException.class,
                    () -> f.domain.visible(f.request, descriptor(), "1h7")).getCode());
            when(manager.getById(eq("host"), eq("1h7"), any())).thenReturn(row);
            assertSame(row, f.domain.visible(f.request, descriptor(), "1h7"));
            f.domain.keyAccess = new ApiKeyAuthorizationService() {
                @Override public void authorize(ApiRequest request, Policy policy) {
                    throw new ClientVisibleException(403, "KeyScopeDenied");
                }
            };
            assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor(), "1h7", Map.of("name", "safe"))).getCode());
            verify(manager, never()).update(anyString(), anyString(), any());

        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void nativeDockerHostFullKeyUsesModelForVisiblePutAndReceipt() throws Exception {
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            var f = fixture(); var row = host(); var manager = f.domain.managers.getResourceManagerByType("host");
            nativeKeyAccess(f, new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null,
                    List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE));
            when(manager.getById(eq("host"), eq("1h7"), any())).thenReturn(row);
            when(manager.update(eq("host"), eq("1h7"), any())).thenReturn(row);
            when(f.domain.objects.loadResource("host", 7L)).thenReturn(row);
            when(f.policy.authorizeObject(row)).thenReturn(row);
            assertEquals("docker", row.getKind());
            assertSame(row, f.domain.visible(f.request, descriptor(), "1h7"));
            assertEquals(Map.of("name", "after"), f.domain.prepare(f.request, descriptor(), "1h7", Map.of("name", "after")));
            assertSame(row, f.domain.dispatch(f.request, descriptor(), "1h7", Map.of("name", "after"), "own-plan"));
            assertSame(row, f.domain.receiptTarget(f.request, descriptor(), Map.of("internalResourceId", 7L, "resourceId", "1h7")));
            verify(manager, times(1)).update(eq("host"), eq("1h7"), any());
            assertSame(f.request, ApiContext.getContext().getApiRequest()); assertSame(f.policy, ApiContext.getContext().getPolicy());
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void nativeDockerHostReadKeyCanReadButRealWriteAuthorizationStillDenies() throws Exception {
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            var f = fixture(); var row = host(); var manager = f.domain.managers.getResourceManagerByType("host");
            nativeKeyAccess(f, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                    List.of(new ApiKeyPolicy.Rule("read-host-project", ApiKeyPolicy.Effect.ALLOW,
                            ApiKeyPolicy.Scope.project("1a77"), Set.of("read"))), ApiKeyPolicy.Purpose.AI_BRIDGE));
            when(manager.getById(eq("host"), eq("1h7"), any())).thenReturn(row);
            assertSame(row, f.domain.visible(f.request, descriptor(), "1h7"));
            var denied = assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor(), "1h7", Map.of("name", "never-written")));
            assertEquals(403, denied.getStatus()); assertEquals("KeyScopeDenied", denied.getCode());
            assertEquals("before", row.getName()); verify(manager, never()).update(anyString(), anyString(), any());
            assertSame(f.request, ApiContext.getContext().getApiRequest()); assertSame(f.policy, ApiContext.getContext().getPolicy());
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void hostSchemaRejectsWrongNativeModelEvenWhenItsKindClaimsHost() throws Exception {
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            var f = fixture(); var manager = f.domain.managers.getResourceManagerByType("host");
            nativeKeyAccess(f, new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null,
                    List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE));
            var wrong = new ServiceRecord(); wrong.setId(7L); wrong.setAccountId(77L); wrong.setKind("host");
            when(manager.getById(eq("host"), eq("1h7"), any())).thenReturn(wrong);
            var denied = assertThrows(ClientVisibleException.class, () -> f.domain.visible(f.request, descriptor(), "1h7"));
            assertEquals(404, denied.getStatus()); assertEquals("NotFound", denied.getCode());
            verify(manager, never()).update(anyString(), anyString(), any());
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void durableSnapshotAndSummaryExcludeMaterialAndObserveExactMetadata() {
        var f = fixture(); var row = host();
        for (String field : List.of("credentials", "secretKey", "agentToken")) DataAccessor.fields(row).withKey(field).set("PRIVATE-CANARY");
        var snapshot = f.domain.snapshot(row);
        assertFalse(snapshot.containsKey("data")); assertFalse(snapshot.toString().contains("PRIVATE-CANARY"));
        assertEquals("host-uuid", snapshot.get("uuid")); assertEquals("node.example.test", snapshot.get("hostname"));
        assertEquals("node.example.test (before)", AiHostMetadataChange.displayName(row));
        assertFalse(f.domain.complete(descriptor(), row, Map.of("name", "after"), snapshot));
        row.setName("after"); assertTrue(f.domain.complete(descriptor(), row, Map.of("name", "after"), snapshot));
        row.setRemoved(new Date()); assertFalse(f.domain.complete(descriptor(), row, Map.of("name", "after"), snapshot));
        assertFalse(f.domain.changes(descriptor(), row, Map.of("name", "after")).toString().contains("PRIVATE-CANARY"));
    }

    @Test public void boundPlanRequiresHumanApprovalAndExactOnceObservedCompletion() {
        var f = preview(); assertEquals(0, f.dispatched);
        denied("AiKeyConfirmationForbidden", () -> f.service.action(f.id, f.request("confirm", true)));
        denied("ResourceChangeNeedsConfirmation", () -> f.apply(true));
        f.confirm(); denied("ResourceChangeBoundKeyRequired", () -> f.apply(false));
        assertEquals("accepted", f.apply(true).get("status")); assertEquals(1, f.dispatched); assertEquals(1, f.claims);
        assertEquals("running", f.service.get(f.id, f.request(null, true)).get("status"));
        f.complete = true; var observed = f.service.get(f.id, f.request(null, true));
        assertEquals("succeeded", observed.get("status")); assertEquals(true, observed.get("verifiedResult"));
        f.apply(true); assertEquals(1, f.dispatched); assertEquals(1, f.claims);
        assertFalse(observed.containsKey("snapshot")); assertFalse(f.audits.toString().contains("new private description"));
    }

    @Test public void casAndLiveWriteRevocationNeverClaimOrDispatch() {
        var f = preview(); var row = (HostRecord) f.domain.visible(f.request(null, true), descriptor(), "1h7"); row.setName("changed-by-ui");
        denied("ResourceChangePlanStale", f::confirm); assertEquals(0, f.claims);
        row.setName("before"); f.writeAllowed = false;
        denied("PermissionDenied", f::confirm); assertEquals(0, f.dispatched);
        f.writeAllowed = true; f.confirm(); f.writeAllowed = false;
        denied("PermissionDenied", () -> f.apply(true)); assertEquals(0, f.claims); assertEquals(0, f.dispatched);
    }

    @Test public void actualHostTableLockRequiresTransactionAndReloadsTheMetadataSnapshot() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:host-metadata;DATABASE_TO_LOWER=TRUE")) {
            var configuration = new DefaultConfiguration().set(connection).set(SQLDialect.H2);
            var sql = DSL.using(configuration); sql.createSchemaIfNotExists("cattle").execute();
            sql.createTable(HOST).columns(HOST.fields()).execute();
            sql.insertInto(HOST).set(HOST.ID, 7L).set(HOST.ACCOUNT_ID, 77L).set(HOST.UUID, "host-uuid")
                    .set(HOST.KIND, "docker").set(HOST.NAME, "before")
                    .set(HOST.STATE, "active").set(HOST.IS_PUBLIC, false).set(HOST.DATA, Map.of()).execute();
            var domain = new AiResourceChangeDomain(); domain.json = new JacksonJsonMapper(); domain.setConfiguration(configuration);
            assertEquals("ResourceChangeTransactionRequired", assertThrows(ClientVisibleException.class,
                    () -> domain.lock("host", 7L)).getCode());
            connection.setAutoCommit(false);
            var before = domain.snapshot(domain.lock("host", 7L));
            sql.update(HOST).set(HOST.NAME, "changed-by-ui").where(HOST.ID.eq(7L)).execute();
            var after = domain.snapshot(domain.lock("host", 7L));
            assertEquals("before", before.get("name")); assertEquals("changed-by-ui", after.get("name"));
            assertNotEquals(before, after); assertFalse(after.containsKey("data")); connection.rollback();
        }
    }

    @Test public void explicitHostResetUsesNativeEmptyNameAndCurrentRoleKeyCeilings() throws Exception {
        var descriptor = descriptor(); Map<String, Object> intent = Map.of("resetName", true);
        assertEquals(intent, descriptor.parameters(intent)); assertEquals(Map.of("name", ""), descriptor.body(intent));
        for (Object bad : List.of(false, "true", 1))
            assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of("resetName", bad)));
        var nullFlag = new LinkedHashMap<String, Object>(); nullFlag.put("resetName", null);
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(nullFlag));
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of("resetName", true, "name", "another")));
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(Map.of("name", "")));
        assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("service", "update_metadata").parameters(intent));
        assertEquals("", io.github.ibuildthecloud.gdapi.validation.ValidationHandler.convertGenericType("name", "",
                io.github.ibuildthecloud.gdapi.model.FieldType.STRING));
        Object previous = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        try {
            ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
            var f = fixture(); var row = host(); var manager = f.domain.managers.getResourceManagerByType("host");
            nativeKeyAccess(f, new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null,
                    List.of(), ApiKeyPolicy.Purpose.AI_BRIDGE));
            when(manager.getById(eq("host"), eq("1h7"), any())).thenReturn(row);
            when(f.domain.objects.loadResource("host", 7L)).thenReturn(row); when(f.policy.authorizeObject(row)).thenReturn(row);
            when(manager.update(eq("host"), eq("1h7"), any())).thenAnswer(call -> {
                ApiRequest request = call.getArgument(2); assertEquals(Map.of("name", ""), request.getRequestObject());
                assertEquals("PUT", request.getMethod()); assertNull(request.getAction()); row.setName(""); return row;
            });
            f.name.setUpdate(false);
            assertEquals(403, assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor, "1h7", intent)).getStatus());
            verify(manager, never()).update(anyString(), anyString(), any()); f.name.setUpdate(true);
            assertEquals(Map.of("name", ""), f.domain.prepare(f.request, descriptor, "1h7", intent));
            assertSame(row, f.domain.dispatch(f.request, descriptor, "1h7", intent, "own-reset"));
            assertEquals("node.example.test", AiHostMetadataChange.displayName(row));
            nativeKeyAccess(f, new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                    List.of(new ApiKeyPolicy.Rule("read", ApiKeyPolicy.Effect.ALLOW,
                            ApiKeyPolicy.Scope.project("1a77"), Set.of("read"))), ApiKeyPolicy.Purpose.AI_BRIDGE));
            assertEquals(403, assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor, "1h7", intent)).getStatus());
            verify(manager, times(1)).update(anyString(), anyString(), any());
        } finally {
            if (previous == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
            else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previous);
        }
    }

    @Test public void resetPreviewAndTerminalResultPinTheOriginalHostnameAndHumanPlan() {
        var domain = new AiResourceChangeDomain(); domain.json = new JacksonJsonMapper(); var row = host();
        Map<String, Object> intent = Map.of("resetName", true), before = domain.snapshot(row);
        var changes = domain.changes(descriptor(), row, intent);
        assertEquals("name", changes.get(0).get("field")); assertEquals("", changes.get(0).get("after"));
        assertEquals("hostname", changes.get(1).get("field")); assertEquals("node.example.test", changes.get(1).get("after"));
        assertEquals(changes.get(1).get("before"), changes.get(1).get("after"));
        assertFalse(domain.complete(descriptor(), row, intent, before)); row.setName("");
        assertTrue(domain.complete(descriptor(), row, intent, before));
        for (Object invalidId : List.of(7.5, Double.NaN, "7", 0, -1)) {
            var invalidBefore = new LinkedHashMap<>(before); invalidBefore.put("id", invalidId);
            assertFalse(domain.complete(descriptor(), row, intent, invalidBefore));
        }
        DataAccessor.fields(row).withKey("hostname").set("changed.example.test");
        assertFalse(domain.complete(descriptor(), row, intent, before));
        DataAccessor.fields(row).withKey("hostname").set("node.example.test"); row.setUuid("changed");
        assertFalse(domain.complete(descriptor(), row, intent, before)); row.setUuid("host-uuid");
        var f = preview(intent); assertEquals(0, f.dispatched);
        denied("ResourceChangeNeedsConfirmation", () -> f.apply(true));
        var current = (HostRecord) f.domain.visible(f.request(null, true), descriptor(), "1h7");
        DataAccessor.fields(current).withKey("hostname").set("changed.example.test");
        denied("ResourceChangePlanStale", f::confirm); assertEquals(0, f.claims);
        DataAccessor.fields(current).withKey("hostname").set("node.example.test"); f.confirm();
        assertEquals("accepted", f.apply(true).get("status")); assertEquals(1, f.dispatched);
        f.complete = true; assertEquals("succeeded", f.service.get(f.id, f.request(null, true)).get("status"));
        f.apply(true); assertEquals(1, f.dispatched); assertEquals(1, f.claims);
    }

    private static AiResourceChangeDescriptor descriptor() { return AiResourceChangeDescriptor.require("host", "update_metadata"); }

    private static HostRecord host() {
        var row = new HostRecord(); row.setId(7L); row.setAccountId(77L); row.setUuid("host-uuid");
        row.setKind("docker"); row.setName("before"); DataAccessor.fields(row).withKey("hostname").set("node.example.test");
        row.setState("active"); return row;
    }

    private static AiResourceChangeDomainTest.Fixture fixture() {
        var f = new AiResourceChangeDomainTest.Fixture(); when(f.schemas.getSchema("host")).thenReturn(f.schema);
        when(f.schema.getResourceMethods()).thenReturn(List.of("GET", "PUT"));
        FieldImpl description = new FieldImpl(); description.setUpdate(true);
        when(f.schema.getResourceFields()).thenReturn(Map.of("name", f.name, "description", description));
        f.domain.managers = mock(ResourceManagerLocator.class);
        when(f.domain.managers.getResourceManagerByType("host")).thenReturn(mock(ResourceManager.class)); return f;
    }

    private static void nativeKeyAccess(AiResourceChangeDomainTest.Fixture f, ApiKeyPolicy policy) throws Exception {
        var access = new ApiKeyAuthorizationService(); var targets = mock(ApiKeyTargetResolver.class);
        when(targets.resolve(any(), any())).thenReturn(List.of(ApiKeyPolicyEvaluator.Target.projectResource("host", "1h7", "1a77")));
        for (var field : Map.of("targets", targets, "auditSinks", List.of(mock(ApiKeyAuditSink.class))).entrySet()) {
            var member = ApiKeyAuthorizationService.class.getDeclaredField(field.getKey()); member.setAccessible(true); member.set(access, field.getValue());
        }
        f.domain.keyAccess = access;
        ApiKeyCredentialContext.attach(f.request, new ApiKeyCredentialContext(7, 42, "apiKeyRestricted", 3, policy));
    }

    private static AiResourceChangePlanServiceTest.Fixture preview() {
        return preview(Map.of("description", "new private description"));
    }

    private static AiResourceChangePlanServiceTest.Fixture preview(Map<String, Object> parameters) {
        var f = new AiResourceChangePlanServiceTest.Fixture(); var row = host();
        var snapshotOwner = new AiResourceChangeDomain(); snapshotOwner.json = f.json;
        when(f.domain.visible(any(), any(), anyString())).thenReturn(row);
        when(f.domain.lock(anyString(), anyLong())).thenReturn(row);
        when(f.domain.receiptTarget(any(), any(), anyMap())).thenReturn(row);
        when(f.domain.snapshot(any())).thenAnswer(call -> snapshotOwner.snapshot(call.getArgument(0)));
        doAnswer(call -> {
            f.dispatched++;
            if (Boolean.TRUE.equals(parameters.get("resetName"))) row.setName("");
            else row.setDescription("new private description");
            return row;
        }).when(f.domain).dispatch(any(), any(), nullable(String.class), anyMap(), anyString());
        ApiRequest request = f.request(null, true); request.setId(null);
        request.setRequestObject(Map.of("resourceType", "host", "operation", "update_metadata", "resourceId", "1h7",
                "parameters", parameters));
        var plan = f.service.preview(request); f.id = (String) plan.get("id"); assertEquals("pending", plan.get("confirmationState")); return f;
    }

    private static void denied(String code, Runnable action) { assertEquals(code, assertThrows(ClientVisibleException.class, action::run).getCode()); }
}
