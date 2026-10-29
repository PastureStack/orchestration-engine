package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auditing.dao.impl.AuditLogDaoImpl;
import io.cattle.platform.object.util.DataUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.conf.RenderQuotedNames;
import org.jooq.conf.Settings;
import org.jooq.impl.DSL;
import org.jooq.impl.DataSourceConnectionProvider;
import org.jooq.impl.DefaultConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

public class ApiKeyAuditTransactionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void deniedInnerReadSurvivesOuterWriteRollbackAndIndependentReplay() throws Exception {
        Fixture fixture = fixture();
        ApiRequest denied = request("DENY");
        assertThrows(ClientVisibleException.class, () -> fixture.transaction.execute(status -> {
            fixture.assertCoreTransaction();
            fixture.service.recordDecision(denied, policy());
            throw new ClientVisibleException(403, "KeyScopeDenied");
        }));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(0, fixture.rows());
        assertEquals(1, fixture.pending());
        fixture.replay.run();
        assertEquals(1, fixture.rows());
        assertEquals(0, fixture.pending());
        var stored = fixture.database.selectFrom(io.cattle.platform.core.model.tables.AuditLogTable.AUDIT_LOG).fetchOne();
        assertNotNull(stored);
        assertEquals(5L, stored.getAccountId().longValue());
        assertEquals(1L, stored.getAuthenticatedAsAccountId().longValue());
        assertEquals("DENY", DataUtils.getFields(stored).get("decision"));
        assertEquals("KeyScopeDenied", DataUtils.getFields(stored).get("reason"));
    }

    @Test
    public void ambientPersistAndExplicitReplayCannotDeleteOlderPendingEvents() throws Exception {
        Fixture fixture = fixture();
        fixture.database.execute("alter table audit_log rename to unavailable_audit_log");
        fixture.service.recordDecision(request("DENY"), policy());
        assertEquals(1, fixture.pending());
        fixture.database.execute("alter table unavailable_audit_log rename to audit_log");
        fixture.transaction.executeWithoutResult(status -> {
            fixture.assertCoreTransaction();
            fixture.replay.run();
            assertEquals(1, fixture.pending());
            assertEquals(0, fixture.rows());
            fixture.service.recordDecision(request("ALLOW"), policy());
            assertEquals(2, fixture.pending());
            assertEquals(0, fixture.rows());
            status.setRollbackOnly();
        });
        assertEquals(2, fixture.pending());
        fixture.replay.run();
        assertEquals(2, fixture.rows());
        assertEquals(0, fixture.pending());
    }

    @Test
    public void committedWriteReplaysAfterCommitAndNativeReadStillDeliversImmediately() throws Exception {
        Fixture fixture = fixture();
        fixture.transaction.executeWithoutResult(status -> {
            fixture.assertCoreTransaction();
            fixture.service.recordDecision(request("ALLOW"), policy());
            assertEquals(1, fixture.pending());
            assertEquals(0, fixture.rows());
        });
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertEquals(1, fixture.pending());
        assertEquals(0, fixture.rows());
        fixture.replay.run();
        assertEquals(1, fixture.rows());
        assertEquals(0, fixture.pending());
        fixture.service.recordDecision(request("DENY"), policy());
        assertEquals(2, fixture.rows());
        assertEquals(0, fixture.pending());
    }

    @Test
    public void databaseFailureRetainsRedactedMetadataUntilSuccessfulReplay() throws Exception {
        Fixture fixture = fixture();
        fixture.database.execute("alter table audit_log rename to unavailable_audit_log");
        fixture.service.recordDecision(request("DENY"), policy());
        assertEquals(1, fixture.pending());
        String queued = Files.readString(fixture.pendingFiles().getFirst());
        assertTrue(queued.contains("KeyScopeDenied"));
        assertTrue(queued.contains("1c8605"));
        assertFalse(queued.contains("private-input"));
        assertFalse(queued.contains("private-response"));
        fixture.replay.run();
        assertEquals(1, fixture.pending());
        fixture.database.execute("alter table unavailable_audit_log rename to audit_log");
        fixture.replay.run();
        assertEquals(1, fixture.rows());
        assertEquals(0, fixture.pending());
        fixture.replay.run();
        assertEquals(1, fixture.rows());
    }

    @Test
    public void ambientTransactionStillRejectsUnwritableSpoolBeforeAdmission() throws Exception {
        Fixture fixture = fixture();
        Path invalidDirectory = temporary.newFile().toPath();
        fixture.service.apiKeyOutbox = new ApiKeyAuditOutbox(invalidDirectory, fixture.service.jsonMapper);
        ApiRequest request = request("DENY");
        assertThrows(IllegalStateException.class, () -> fixture.transaction.execute(status -> {
            fixture.assertCoreTransaction();
            fixture.service.recordDecision(request, policy());
            return null;
        }));
        assertNotEquals(Boolean.TRUE, request.getAttribute("apiKey.audit.admitted"));
        assertEquals(0, fixture.rows());
    }

    private Fixture fixture() throws Exception {
        DriverManagerDataSource source = new DriverManagerDataSource("jdbc:h2:mem:audit-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        DefaultConfiguration configuration = new DefaultConfiguration();
        configuration.set(SQLDialect.H2);
        // Match SystemConfig: both the Core transaction manager and jOOQ use
        // the same physical source through Spring's transaction-aware proxy.
        configuration.set(new DataSourceConnectionProvider(new TransactionAwareDataSourceProxy(source)));
        configuration.set(new Settings().withRenderSchema(false).withRenderQuotedNames(RenderQuotedNames.NEVER));
        DSLContext database = DSL.using(configuration);
        database.execute("create table audit_log (id bigint generated by default as identity primary key, "
                + "kind varchar(255), created timestamp, account_id bigint, authenticated_as_account_id bigint, "
                + "resource_type varchar(255), resource_id bigint, data clob, authenticated_as_identity_id bigint, "
                + "event_type varchar(255), auth_type varchar(255), runtime bigint, description clob, client_ip varchar(255))");
        AuditLogDaoImpl dao = new AuditLogDaoImpl();
        dao.setConfiguration(configuration);
        JacksonMapper mapper = new JacksonMapper();
        mapper.init();
        AuditServiceImpl service = new AuditServiceImpl();
        service.auditLogDao = dao;
        service.jsonMapper = mapper;
        Path directory = privateDirectory();
        service.apiKeyOutbox = new ApiKeyAuditOutbox(directory, mapper);
        AtomicReference<Runnable> replay = new AtomicReference<>();
        service.executor = (ScheduledExecutorService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { ScheduledExecutorService.class }, (proxy, method, args) -> {
                    assertEquals("scheduleWithFixedDelay", method.getName());
                    assertEquals(5L, args[1]);
                    assertEquals(5L, args[2]);
                    assertEquals(TimeUnit.SECONDS, args[3]);
                    replay.set((Runnable) args[0]);
                    return null;
                });
        service.start();
        assertNotNull(replay.get());
        return new Fixture(service, directory, database, source, replay.get());
    }

    private Path privateDirectory() throws java.io.IOException {
        Path directory = temporary.newFolder().toPath();
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
        } else {
            // Windows temporary folders can inherit writable group ACLs. The
            // test must meet the real spool's private-owner requirement too.
            var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            assertNotNull(acl);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(directory))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT).build()));
        }
        return directory;
    }

    private static ApiRequest request(String decision) {
        ApiRequest request = new ApiRequest(null, null);
        request.setMethod("GET");
        request.setType("stack");
        request.setAttribute("apiKey.audit.keyId", "1c8605");
        request.setAttribute("apiKey.audit.decision", decision);
        request.setAttribute("apiKey.audit.reason", "DENY".equals(decision) ? "KeyScopeDenied" : "KeyScopedCollection");
        request.setAttribute("apiKey.audit.operation", "read");
        request.setAttribute("apiKey.audit.targetType", "stack");
        request.setAttribute("apiKey.audit.targetId", "");
        request.setAttribute("apiKey.audit.policyRevision", 1);
        request.setRequestObject(Map.of("password", "private-input"));
        request.setResponseObject(Map.of("secretValue", "private-response"));
        return request;
    }

    private static Policy policy() {
        return (Policy) Proxy.newProxyInstance(Policy.class.getClassLoader(), new Class<?>[] { Policy.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "getAccountId" -> 5L;
                    case "getAuthenticatedAsAccountId" -> 1L;
                    default -> null;
                });
    }

    private static final class Fixture {
        final AuditServiceImpl service;
        final Path directory;
        final DSLContext database;
        final DriverManagerDataSource source;
        final TransactionTemplate transaction;
        final Runnable replay;

        Fixture(AuditServiceImpl service, Path directory, DSLContext database, DriverManagerDataSource source, Runnable replay) {
            this.service = service;
            this.directory = directory;
            this.database = database;
            this.source = source;
            transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
            this.replay = replay;
        }

        void assertCoreTransaction() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            Connection connection = DataSourceUtils.getConnection(source);
            try {
                assertTrue(DataSourceUtils.isConnectionTransactional(connection, source));
                assertFalse(connection.getAutoCommit());
            } catch (java.sql.SQLException failure) {
                throw new AssertionError(failure);
            } finally {
                DataSourceUtils.releaseConnection(connection, source);
            }
        }

        int rows() {
            return database.fetchCount(DSL.table("audit_log"));
        }

        List<Path> pendingFiles() throws java.io.IOException {
            try (var files = Files.list(directory)) {
                return files.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
            }
        }

        int pending() {
            try {
                return pendingFiles().size();
            } catch (java.io.IOException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
