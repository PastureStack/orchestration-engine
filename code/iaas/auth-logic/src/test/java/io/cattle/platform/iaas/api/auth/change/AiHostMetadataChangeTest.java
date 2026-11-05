package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.HostTable.HOST;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
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
        for (String operation : List.of("create", "remove", "start", "stop", "restore", "update_targets"))
            assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("host", operation));
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
            row.setAccountId(77L); row.setKind("service");
            assertEquals("NotFound", assertThrows(ClientVisibleException.class,
                    () -> f.domain.visible(f.request, descriptor(), "1h7")).getCode());
            row.setKind("host"); assertSame(row, f.domain.visible(f.request, descriptor(), "1h7"));
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
                    .set(HOST.KIND, "host").set(HOST.NAME, "before")
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

    private static AiResourceChangeDescriptor descriptor() { return AiResourceChangeDescriptor.require("host", "update_metadata"); }

    private static HostRecord host() {
        var row = new HostRecord(); row.setId(7L); row.setAccountId(77L); row.setUuid("host-uuid");
        row.setKind("host"); row.setName("before"); DataAccessor.fields(row).withKey("hostname").set("node.example.test");
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

    private static AiResourceChangePlanServiceTest.Fixture preview() {
        var f = new AiResourceChangePlanServiceTest.Fixture(); var row = host();
        var snapshotOwner = new AiResourceChangeDomain(); snapshotOwner.json = f.json;
        when(f.domain.visible(any(), any(), anyString())).thenReturn(row);
        when(f.domain.lock(anyString(), anyLong())).thenReturn(row);
        when(f.domain.receiptTarget(any(), any(), anyMap())).thenReturn(row);
        when(f.domain.snapshot(any())).thenAnswer(call -> snapshotOwner.snapshot(call.getArgument(0)));
        doAnswer(call -> {
            f.dispatched++; row.setDescription("new private description"); return row;
        }).when(f.domain).dispatch(any(), any(), nullable(String.class), anyMap(), anyString());
        ApiRequest request = f.request(null, true); request.setId(null);
        request.setRequestObject(Map.of("resourceType", "host", "operation", "update_metadata", "resourceId", "1h7",
                "parameters", Map.of("description", "new private description")));
        var plan = f.service.preview(request); f.id = (String) plan.get("id"); assertEquals("pending", plan.get("confirmationState")); return f;
    }

    private static void denied(String code, Runnable action) { assertEquals(code, assertThrows(ClientVisibleException.class, action::run).getCode()); }
}
