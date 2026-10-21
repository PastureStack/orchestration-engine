package io.cattle.platform.iaas.api.auditing;

import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class ApiKeyAuditDirectoryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private ApiKeyAuditOutbox outbox(Path path) {
        JacksonMapper mapper = new JacksonMapper(); mapper.init();
        return new ApiKeyAuditOutbox(path, mapper);
    }

    private Path root() throws IOException { return temporary.newFolder().toPath(); }
    private static String eventName() { return "1-" + UUID.randomUUID() + ".json"; }
    private static void offline(Map<String, Object> event) { throw new IllegalStateException("offline"); }
    private static void denied(ApiKeyAuditOutbox box) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> box.persist(Map.of("eventId", "new"), ApiKeyAuditDirectoryTest::offline));
        assertEquals("API-key audit persistence is unavailable", failure.getMessage());
    }
    private static void symlink(Path link, Path target) throws IOException {
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            Assume.assumeNoException("Host does not permit symbolic-link creation", unavailable);
        }
    }

    @Test public void openedSecureHandleMustMatchThePinnedDirectoryNotTheCurrentPath() throws Exception {
        Path spool = root(), replacement = root();
        ApiKeyAuditDirectory directory = new ApiKeyAuditDirectory(spool);
        try (var access = directory.open(false);
             var realHandle = Files.newDirectoryStream(spool);
             var wrongHandle = Files.newDirectoryStream(replacement)) {
            Assume.assumeTrue(realHandle instanceof java.nio.file.SecureDirectoryStream<?>);
            directory.validateOpenedDirectory(realHandle);
            IOException rejected = assertThrows(IOException.class, () -> directory.validateOpenedDirectory(wrongHandle));
            assertEquals("Audit directory handle changed", rejected.getMessage());
            assertTrue(Files.isSameFile(spool, spool)); // Restoring the pathname cannot legitimize the wrong handle.
        }
    }

    @Test public void linuxServiceOwnershipDoesNotDependOnTheJvmUserName() throws Exception {
        Assume.assumeTrue(System.getProperty("os.name", "").equals("Linux"));
        String original = System.getProperty("user.name");
        try {
            System.setProperty("user.name", "unmapped-pasturestack-service-uid");
            ApiKeyAuditDirectory directory = new ApiKeyAuditDirectory(root());
            try (var access = directory.open(false)) { assertNotNull(access); }
        } finally {
            if (original == null) System.clearProperty("user.name");
            else System.setProperty("user.name", original);
        }
    }

    @Test public void customAbsoluteAndRelativeDirectoriesRetainEventsAcrossRestart() throws Exception {
        for (boolean relative : List.of(false, true)) {
            Path absolute = root().resolve("custom audit 事件").resolve("unused").resolve("..").resolve("spool").normalize();
            Path configured = relative ? Path.of("").toAbsolutePath().relativize(absolute) : absolute;
            outbox(configured).persist(Map.of("eventId", "first"), ApiKeyAuditDirectoryTest::offline);
            List<Map<String, Object>> delivered = new ArrayList<>();
            outbox(configured).replayPending(delivered::add);
            assertEquals("first", delivered.get(0).get("eventId"));
            assertTrue(Files.isDirectory(absolute));
        }
    }

    @Test public void intentionalConfiguredSymlinkIsPinnedNotSilentlyRedirected() throws Exception {
        Path parent = root(), actual = Files.createDirectory(parent.resolve("actual")), alias = parent.resolve("configured");
        symlink(alias, actual);
        ApiKeyAuditOutbox box = outbox(alias);
        box.persist(Map.of("eventId", "first"), ApiKeyAuditDirectoryTest::offline);
        Files.delete(alias);
        Path replacement = Files.createDirectory(parent.resolve("replacement"));
        symlink(alias, replacement);
        denied(box);
        try (var files = Files.list(actual)) { assertEquals(1, files.filter(p -> p.toString().endsWith(".json")).count()); }
        try (var files = Files.list(replacement)) { assertEquals(0, files.count()); }
    }

    @Test public void symlinkLockAndTemporaryCannotTouchOutsideSentinel() throws Exception {
        for (String name : List.of("outbox.lock", "outbox.pending")) {
            Path spool = root(), outside = temporary.newFile().toPath();
            Files.writeString(outside, "outside must remain unchanged");
            symlink(spool.resolve(name), outside);
            denied(outbox(spool));
            assertEquals("outside must remain unchanged", Files.readString(outside));
        }
    }

    @Test public void symlinkEventOrTerminalCannotBeDeliveredOrAcknowledged() throws Exception {
        for (String name : List.of(eventName(), "terminal-" + "a".repeat(64) + ".json")) {
            Path spool = root(), outside = temporary.newFile().toPath();
            Files.writeString(outside, "{\"eventId\":\"outside\"}");
            symlink(spool.resolve(name), outside);
            List<Map<String, Object>> delivered = new ArrayList<>();
            assertThrows(IllegalStateException.class, () -> outbox(spool).replayPending(delivered::add));
            assertTrue(delivered.isEmpty());
            assertEquals("{\"eventId\":\"outside\"}", Files.readString(outside));
        }
    }

    @Test public void hardLinkedTemporaryNeverTruncatesOutsideSentinel() throws Exception {
        Path spool = root(), outside = temporary.newFile().toPath();
        Files.writeString(outside, "outside hardlink sentinel");
        try { Files.createLink(spool.resolve("outbox.pending"), outside); }
        catch (UnsupportedOperationException | java.nio.file.FileSystemException unsupported) { Assume.assumeNoException(unsupported); }
        try { outbox(spool).persist(Map.of("eventId", "new"), ApiKeyAuditDirectoryTest::offline); }
        catch (IllegalStateException rejected) { assertEquals("API-key audit persistence is unavailable", rejected.getMessage()); }
        assertEquals("outside hardlink sentinel", Files.readString(outside));
    }

    @Test public void stalePrivateTemporaryIsReplacedWithoutReusingItsInode() throws Exception {
        Path spool = root();
        Files.writeString(spool.resolve("outbox.pending"), "unacknowledged interrupted event");
        outbox(spool).persist(Map.of("eventId", "durable"), ApiKeyAuditDirectoryTest::offline);
        List<Map<String, Object>> delivered = new ArrayList<>();
        outbox(spool).replayPending(delivered::add);
        assertEquals(1, delivered.size()); assertEquals("durable", delivered.get(0).get("eventId"));
        assertFalse(Files.exists(spool.resolve("outbox.pending")));
    }

    @Test public void directoryReplacementDoesNotWriteToTheReplacement() throws Exception {
        Path parent = root(), spool = Files.createDirectory(parent.resolve("spool"));
        ApiKeyAuditOutbox box = outbox(spool);
        box.persist(Map.of("eventId", "first"), ApiKeyAuditDirectoryTest::offline);
        Files.move(spool, parent.resolve("original"), StandardCopyOption.ATOMIC_MOVE);
        Files.createDirectory(spool);
        denied(box);
        try (var files = Files.list(spool)) { assertEquals(0, files.count()); }
    }

    @Test public void directoryAndUnexpectedJsonCannotBeTreatedAsEvents() throws Exception {
        for (boolean directory : List.of(false, true)) {
            Path spool = root();
            if (directory) Files.createDirectory(spool.resolve(eventName()));
            else Files.writeString(spool.resolve("unexpected.json"), "{}");
            denied(outbox(spool));
        }
    }

    @Test public void boundedReadUsesItsOpenedHandleAndRejectsOversize() throws Exception {
        Path spool = root(); ApiKeyAuditDirectory directory = new ApiKeyAuditDirectory(spool);
        try (var files = directory.open(true)) {
            Path name = files.leaf(eventName());
            try (var channel = files.openFile(name, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(new byte[ApiKeyAuditOutbox.MAX_EVENT_BYTES + 1]));
            }
            assertThrows(IOException.class, () -> files.read(name, ApiKeyAuditOutbox.MAX_EVENT_BYTES));
        }
    }

    @Test public void allLeafEntryPointsRejectTraversalAndAbsoluteNames() throws Exception {
        try (var files = new ApiKeyAuditDirectory(root()).open(true)) {
            for (String name : List.of("..", ".", "../outside", "nested/file", "nested\\file", root().toString())) {
                assertThrows(IOException.class, () -> files.leaf(name));
                assertThrows(IOException.class, () -> files.openFile(Path.of(name), StandardOpenOption.CREATE, StandardOpenOption.WRITE));
            }
        }
    }

    @Test public void writableByOtherUsersIsRejectedWithoutChangingPermissions() throws Exception {
        Path spool = root();
        Assume.assumeTrue(Files.getFileStore(spool).supportsFileAttributeView("posix"));
        var unsafe = PosixFilePermissions.fromString("rwxrwxrwx");
        Files.setPosixFilePermissions(spool, unsafe);
        denied(outbox(spool));
        assertEquals(unsafe, Files.getPosixFilePermissions(spool));
    }

    @Test public void newDirectoryAndFilesArePrivateOnPosix() throws Exception {
        Path parent = root();
        Assume.assumeTrue(Files.getFileStore(parent).supportsFileAttributeView("posix"));
        Path spool = parent.resolve("new-spool");
        outbox(spool).persist(Map.of("eventId", "private"), ApiKeyAuditDirectoryTest::offline);
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(spool));
        try (var files = Files.list(spool)) {
            for (Path path : files.toList()) assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path));
        }
    }
}
