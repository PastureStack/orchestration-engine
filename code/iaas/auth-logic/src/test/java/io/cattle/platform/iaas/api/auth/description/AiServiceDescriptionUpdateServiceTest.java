package io.cattle.platform.iaas.api.auth.description;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;

import io.cattle.platform.api.auth.ApiKeyAuditSink;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.junit.After;
import org.junit.Test;

public class AiServiceDescriptionUpdateServiceTest {
    @After public void removeContext() { ApiContext.remove(); }

    @Test public void exactUnicodeAndEmptyUpdatesDispatchOnlyDescription() {
        Fixture f = new Fixture(false);
        Map<String, Object> before = new LinkedHashMap<>(f.target.intoMap());
        String text = "  說明\nemoji 🧪 e\u0301 <>&  ";
        Map<String, Object> result = f.run("old", text);
        assertEquals(text, result.get("description")); assertEquals("succeeded", result.get("status"));
        assertEquals(true, result.get("verifiedResult")); assertEquals("10", result.get("serviceId"));
        assertEquals(Map.of("description", text), f.dispatched.getRequestObject());
        assertEquals("PUT", f.dispatched.getMethod()); assertEquals("service", f.dispatched.getType());
        assertEquals("10", f.dispatched.getId()); assertNull(f.dispatched.getAction());
        before.put("description", text); assertEquals(before, f.target.intoMap());
        assertEquals("", f.run(text, "").get("description"));
        verify(f.audit, times(2)).recordServiceDescriptionDecision(any(), same(f.policy), anyString(), eq(10L), eq(true), eq("DescriptionUpdateAccepted"));
    }

    @Test public void nullAndEmptyAreDifferentSnapshotsAndLengthUsesUtf16() {
        Fixture f = new Fixture(false); f.target.setDescription(null);
        assertCode(409, "DescriptionConflict", () -> f.run("", "new"));
        assertEquals("", f.run(null, "").get("description"));
        assertCode(409, "DescriptionConflict", () -> f.run(null, "new"));
        String maximum = "🧪".repeat(512);
        assertEquals(maximum, f.run("", maximum).get("description"));
        assertCode(422, "DescriptionInputInvalid", () -> f.run(maximum, maximum + "x"));
        assertCode(422, "DescriptionInputInvalid", () -> f.run("x".repeat(1025), "new"));
    }

    @Test public void missingSnapshotWrongTypesAndAdditionalFieldsCannotWrite() {
        Fixture f = new Fixture(false);
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("serviceId", "10", "description", "new"),
                Map.of("serviceId", "10", "description", 1, "expectedDescription", "old"),
                Map.of("serviceId", "10", "description", "new", "expectedDescription", false),
                Map.of("serviceId", "10", "description", "new", "expectedDescription", "old", "name", "evil"),
                Map.of("serviceId", "10", "description", "new", "expectedDescription", "old", "id", "11"))) {
            assertCode(422, "DescriptionInputInvalid", () -> f.service.update(f.request(body)));
        }
        verify(f.manager, never()).update(anyString(), anyString(), any());
    }

    @Test public void readonlyFieldRevocationAndVisibilityAreCheckedEveryTime() {
        Fixture f = new Fixture(false);
        f.schema.setUpdate(false); assertCode(403, "PermissionDenied", () -> f.run("old", "new"));
        f.schema.setUpdate(true); f.description.setUpdate(false);
        assertCode(403, "PermissionDenied", () -> f.run("old", "new"));
        f.description.setUpdate(true); f.schema.getResourceFields().remove("description");
        assertCode(403, "PermissionDenied", () -> f.run("old", "new"));
        f.schema.getResourceFields().put("description", f.description);
        when(f.manager.getById(anyString(), anyString(), any())).thenReturn(null);
        assertCode(404, "NotFound", () -> f.run("old", "new"));
        verify(f.manager, never()).update(anyString(), anyString(), any());
        verify(f.audit, times(4)).recordServiceDescriptionDecision(any(), same(f.policy), anyString(), eq(10L), eq(false), anyString());
    }

    @Test public void crossProjectCrossIdRemovedTargetInactiveActorAndRevokedKeyAreRejected() {
        Fixture f = new Fixture(false);
        when(f.policy.getAccountId()).thenReturn(99L);
        assertCode(404, "NotFound", () -> f.run("old", "new"));
        when(f.policy.getAccountId()).thenReturn(77L);
        ServiceRecord other = new ServiceRecord(); other.from(f.target); other.setId(11L);
        when(f.manager.getById(anyString(), anyString(), any())).thenReturn(other);
        assertCode(404, "NotFound", () -> f.run("old", "new"));
        when(f.manager.getById(anyString(), anyString(), any())).thenReturn(f.target);
        f.target.setRemoved(new java.util.Date()); assertCode(404, "NotFound", () -> f.run("old", "new"));
        f.target.setRemoved(null); f.actor.setState("inactive");
        assertCode(403, "PermissionDenied", () -> f.run("old", "new"));
        f.actor.setState("active"); ApiRequest keyRequest = f.request(f.body("old", "new"));
        ApiKeyCredentialContext.attach(keyRequest, new ApiKeyCredentialContext(1L, 42L, "apiKey", 1L, null));
        doThrow(new ClientVisibleException(403, "ApiKeyPolicyChanged")).when(f.service.keyAuthorization).actor(keyRequest);
        assertCode(403, "ApiKeyPolicyChanged", () -> f.service.update(keyRequest));
        verify(f.manager, never()).update(anyString(), anyString(), any());
    }

    @Test public void conflictingOrUnverifiedWritesNeverClaimSuccess() {
        Fixture f = new Fixture(false);
        assertCode(409, "DescriptionConflict", () -> f.run("stale", "new"));
        verify(f.manager, never()).update(anyString(), anyString(), any());
        doReturn(f.target).when(f.manager).update(anyString(), anyString(), any());
        assertCode(409, "DescriptionResultMismatch", () -> f.run("old", "new"));
        assertEquals("old", f.target.getDescription());
        f.service.auditSinks = List.of();
        assertCode(503, "DescriptionAuditUnavailable", () -> f.run("old", "new"));
    }

    @Test public void committedUiWriteWinsAndStaleDescriptionCannotOverwriteIt() throws Exception {
        try (Database db = new Database(); Connection ai = db.open(); Connection ui = db.open()) {
            ui.createStatement().executeUpdate("update service set description='UI edit', name='kept name' where id=10");
            ui.commit();
            Fixture f = new Fixture(true); f.database(ai);
            assertCode(409, "DescriptionConflict", () -> f.run("old", "AI edit"));
            ai.rollback();
            assertEquals("UI edit", db.description());
            verify(f.manager, never()).update(anyString(), anyString(), any());
            // Reverting to an earlier value still uses the current locking snapshot.
            assertEquals("old", f.run("UI edit", "old").get("description")); ai.commit();
            assertEquals("kept name", db.dsl().select(SERVICE.NAME).from(SERVICE).where(SERVICE.ID.eq(10L)).fetchOne(0));
        }
    }

    @Test public void aiLockBlocksOrdinaryUiUpdateUntilTransactionCommit() throws Exception {
        try (Database db = new Database(); Connection ai = db.open(); Connection ui = db.open(); var pool = Executors.newSingleThreadExecutor()) {
            Fixture f = new Fixture(true); f.database(ai);
            assertEquals("AI edit", f.run("old", "AI edit").get("description"));
            CountDownLatch attempted = new CountDownLatch(1);
            var uiWrite = pool.submit(() -> {
                attempted.countDown();
                return ui.createStatement().executeUpdate("update service set description='later UI edit' where id=10");
            });
            assertTrue(attempted.await(2, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> uiWrite.get(150, TimeUnit.MILLISECONDS));
            ai.commit(); assertEquals(1, (int) uiWrite.get(3, TimeUnit.SECONDS)); ui.commit();
            assertEquals("later UI edit", db.description());
        }
    }

    @Test public void autocommitCannotSilentlyReleaseTheSnapshotLock() throws Exception {
        try (Database db = new Database(); Connection ai = db.open()) {
            ai.setAutoCommit(true); Fixture f = new Fixture(true); f.database(ai);
            assertCode(503, "DescriptionTransactionRequired", () -> f.run("old", "new"));
            verify(f.manager, never()).update(anyString(), anyString(), any());
        }
    }

    @Test public void normalApiSerializationPreservesEmptyAndNullDescriptions() throws Exception {
        var json = new io.github.ibuildthecloud.gdapi.json.JacksonMapper(); json.init();
        for (String description : new String[] { "", null, "  說明 🧪  " }) {
            Map<String, Object> fields = new LinkedHashMap<>(); fields.put("description", description);
            var resource = new io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl("10", "service", fields);
            var bytes = new java.io.ByteArrayOutputStream(); json.writeValue(bytes, resource);
            Map<?, ?> read = json.readValue(bytes.toByteArray(), Map.class);
            assertTrue(read.containsKey("description")); assertEquals(description, read.get("description"));
        }
    }

    @Test public void apiValidationKeepsRequiredNullSnapshotAndEmptyNewDescription() throws Exception {
        Fixture f = new Fixture(false); f.target.setDescription(null);
        SchemaImpl synthetic = new SchemaImpl(); synthetic.setId("aiServiceDescriptionUpdate"); synthetic.setCreate(true);
        for (String fieldName : List.of("serviceId", "description", "expectedDescription")) {
            FieldImpl field = new FieldImpl(); field.setCreate(true); field.setRequired(true);
            field.setType("serviceId".equals(fieldName) ? "reference[service]" : "string");
            field.setNullable("expectedDescription".equals(fieldName));
            synthetic.getResourceFields().put(fieldName, field);
        }
        when(f.schemas.getSchema("aiServiceDescriptionUpdate")).thenReturn(synthetic);
        ApiRequest request = f.request(f.body(null, ""));
        var validation = new io.github.ibuildthecloud.gdapi.validation.ValidationHandler(); validation.init(); validation.generate(request);
        Map<?, ?> validated = (Map<?, ?>) request.getRequestObject();
        assertTrue(validated.containsKey("expectedDescription")); assertNull(validated.get("expectedDescription"));
        assertEquals("", validated.get("description")); assertEquals(10L, validated.get("serviceId"));
        assertEquals("", f.service.update(request).get("description"));
    }

    private static void assertCode(int status, String code, Runnable action) {
        ClientVisibleException error = assertThrows(ClientVisibleException.class, action::run);
        assertEquals(status, error.getStatus()); assertEquals(code, error.getCode());
    }

    private static class Fixture {
        final Policy policy = mock(Policy.class);
        final SchemaFactory schemas = mock(SchemaFactory.class);
        final SchemaImpl schema = new SchemaImpl();
        final FieldImpl description = new FieldImpl();
        final ResourceManager manager = mock(ResourceManager.class);
        final ApiKeyAuditSink audit = mock(ApiKeyAuditSink.class);
        final ServiceRecord target = new ServiceRecord();
        final AccountRecord actor = new AccountRecord();
        final AiServiceDescriptionUpdateService service;
        ApiRequest dispatched;

        Fixture(boolean realLock) {
            service = realLock ? new AiServiceDescriptionUpdateService() : new AiServiceDescriptionUpdateService() {
                @Override protected Service lockTarget(long id) { return id == 10 ? target : null; }
            };
            when(policy.getAuthenticatedAsAccountId()).thenReturn(42L); when(policy.getAccountId()).thenReturn(77L);
            actor.setId(42L); actor.setState("active");
            service.objects = mock(ObjectManager.class); when(service.objects.loadResource(Account.class, 42L)).thenReturn(actor);
            service.keyAuthorization = mock(AiKeyAuthorization.class);
            when(service.keyAuthorization.actor(any())).thenReturn(policy);
            when(service.keyAuthorization.authorizeService(any(), any(), eq("update"))).thenReturn(policy);
            target.setId(10L); target.setAccountId(77L); target.setDescription("old"); target.setKind("service");
            target.setName("kept name"); target.setState("active"); target.setStackId(3L);
            target.setData(Map.of("fields", Map.of("launchConfig", Map.of("imageUuid", "docker:app:1"), "scale", 2)));
            schema.setId("service"); schema.setUpdate(true); schema.setList(true);
            description.setUpdate(true); schema.getResourceFields().put("description", description);
            when(schemas.getSchema("service")).thenReturn(schema);
            service.managers = mock(ResourceManagerLocator.class); when(service.managers.getResourceManagerByType("service")).thenReturn(manager);
            service.auditSinks = List.of(audit);
            when(manager.getById(anyString(), anyString(), any())).thenReturn(target);
            doAnswer(call -> {
                dispatched = call.getArgument(2);
                target.setDescription((String) ((Map<?, ?>) dispatched.getRequestObject()).get("description")); return target;
            }).when(manager).update(anyString(), anyString(), any());
            ApiContext.newContext().setPolicy(policy); ApiContext.getContext().setIdFormatter(new IdentityFormatter());
        }

        void database(Connection connection) {
            DSLContext db = dsl(connection); service.setConfiguration(db.configuration());
            when(manager.getById(anyString(), anyString(), any())).thenAnswer(call -> db.selectFrom(SERVICE).where(SERVICE.ID.eq(10L)).fetchOne());
            doAnswer(call -> {
                dispatched = call.getArgument(2);
                assertEquals(List.of("description"), List.copyOf(((Map<?, ?>) dispatched.getRequestObject()).keySet()));
                String next = (String) ((Map<?, ?>) dispatched.getRequestObject()).get("description");
                ServiceRecord row = db.selectFrom(SERVICE).where(SERVICE.ID.eq(10L)).fetchOne();
                if (!java.util.Objects.equals(row.getDescription(), next)) {
                    db.update(SERVICE).set(SERVICE.DESCRIPTION, next).where(SERVICE.ID.eq(10L)).execute();
                }
                return db.selectFrom(SERVICE).where(SERVICE.ID.eq(10L)).fetchOne();
            }).when(manager).update(anyString(), anyString(), any());
        }

        Map<String, Object> body(String expected, String value) {
            Map<String, Object> body = new LinkedHashMap<>(); body.put("serviceId", "10");
            body.put("description", value); body.put("expectedDescription", expected); return body;
        }
        ApiRequest request(Map<String, Object> body) {
            ApiRequest request = new ApiRequest(null, schemas); request.setMethod("POST");
            request.setType("aiServiceDescriptionUpdate"); request.setRequestObject(body); return request;
        }
        Map<String, Object> run(String expected, String value) { return service.update(request(body(expected, value))); }
    }

    private static DSLContext dsl(Connection connection) {
        return DSL.using(connection, SQLDialect.H2, new Settings().withRenderSchema(false));
    }

    private static class Database implements AutoCloseable {
        final String url = "jdbc:h2:mem:description-" + UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE;LOCK_TIMEOUT=5000";
        final Connection owner;
        Database() throws Exception {
            owner = DriverManager.getConnection(url);
            // Preserve the generated field types but omit production defaults and FK setup.
            dsl().createTable(SERVICE).columns(Arrays.stream(SERVICE.fields())
                    .map(field -> DSL.field(field.getUnqualifiedName(), field.getDataType().getSQLDataType().nullable(true))).toList()).execute();
            dsl().insertInto(SERVICE).set(SERVICE.ID, 10L).set(SERVICE.ACCOUNT_ID, 77L).set(SERVICE.KIND, "service")
                    .set(SERVICE.NAME, "kept name").set(SERVICE.STATE, "active").set(SERVICE.DESCRIPTION, "old").execute();
        }
        DSLContext dsl() { return AiServiceDescriptionUpdateServiceTest.dsl(owner); }
        Connection open() throws Exception { Connection result = DriverManager.getConnection(url); result.setAutoCommit(false); return result; }
        String description() { return dsl().select(SERVICE.DESCRIPTION).from(SERVICE).where(SERVICE.ID.eq(10L)).fetchOne(0, String.class); }
        @Override public void close() throws Exception { owner.close(); }
    }
}
