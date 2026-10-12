package io.cattle.platform.iaas.api.manager;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class HaConfigDumpTest {
    private static final byte[] SQL = "-- synthetic test dump\nSELECT 1;\n".getBytes(StandardCharsets.UTF_8);
    private static final String PRIVATE = "synthetic-private-diagnostic";
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void successfulDumpIsVerifiedBeforeHttpAndZipContainsExactBytes() throws Exception {
        TestManager manager = manager();
        TestRequest request = new TestRequest();
        request.beforeHttp = () -> {
            assertEquals(List.of(300L), manager.process.waits);
            assertFalse(manager.process.isAlive());
        };

        assertNotNull(manager.getLinkInternal("haConfig", "haConfig", "dbdump", request));

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(request.response.bytes.toByteArray()))) {
            assertTrue(zip.getNextEntry().getName().matches("pasturestack-db-dump-.*[.]sql"));
            assertArrayEquals(SQL, zip.readAllBytes());
            assertNull(zip.getNextEntry());
        }
        assertEquals("application/octet-stream", request.response.contentType);
        assertEquals("private", request.response.headers.get("Cache-Control"));
        assertEquals("password", manager.childPassword);
        assertFalse(manager.builder.environment().containsKey("MYSQL_PWD"));
        assertEquals(ProcessBuilder.Redirect.DISCARD, manager.builder.redirectError());
        manager.assertClean();
    }

    @Test
    public void failedCommandWithOutputCannotCommitHttpOrExposeDiagnostics() throws Exception {
        TestManager manager = manager();
        manager.process.exitCode = 1;
        manager.bytes = PRIVATE.getBytes(StandardCharsets.UTF_8);
        assertDumpFailure(manager, new TestRequest());
    }

    @Test
    public void successfulExitWithEmptyOutputCannotCommitHttp() throws Exception {
        TestManager manager = manager();
        manager.bytes = new byte[0];
        assertDumpFailure(manager, new TestRequest());
    }

    @Test
    public void missingOutputCannotCommitHttp() throws Exception {
        TestManager manager = manager();
        manager.removeOutput = true;
        assertDumpFailure(manager, new TestRequest());
    }

    @Test
    public void launchExceptionRemovesSpoolAndRedactsException() throws Exception {
        TestManager manager = manager();
        manager.launchFailure = new IOException(PRIVATE);
        assertDumpFailure(manager, new TestRequest());
        assertFalse(manager.builder.environment().containsKey("MYSQL_PWD"));
    }

    @Test
    public void temporaryCreationFailureIsRedactedBeforeHttp() throws Exception {
        TestManager manager = manager();
        manager.tempFailure = true;
        assertDumpFailure(manager, new TestRequest());
        assertNull(manager.builder);
    }

    @Test
    public void timeoutForciblyStopsChildAndRemovesSpoolBeforeHttp() throws Exception {
        TestManager manager = manager();
        manager.process.completed = false;
        manager.process.alive = true;
        manager.process.forceRequired = true;
        assertDumpFailure(manager, new TestRequest());
        assertTrue(manager.process.destroyed);
        assertTrue(manager.process.forced);
        assertEquals(List.of(300L, 2L, 2L), manager.process.waits);
    }

    @Test
    public void interruptedCommandStopsChildCleansAndRestoresInterrupt() throws Exception {
        TestManager manager = manager();
        manager.process.interrupt = true;
        manager.process.alive = true;
        try {
            assertDumpFailure(manager, new TestRequest());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(manager.process.destroyed);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void responseCallbackExceptionStillRemovesSpoolAndIsRedacted() throws Exception {
        TestManager manager = manager();
        TestRequest request = new TestRequest();
        request.callbackFailure = new IllegalStateException(PRIVATE);
        expectDumpFailure(manager, request);
        manager.assertClean();
    }

    @Test
    public void responseStreamFailureStillRemovesSpoolAndIsRedacted() throws Exception {
        TestManager manager = manager();
        TestRequest request = new TestRequest();
        request.response.fail = true;
        expectDumpFailure(manager, request);
        manager.assertClean();
    }

    @Test
    public void numericSizeIsBoundedParsedAndCleaned() throws Exception {
        TestManager manager = manager();
        manager.bytes = "12.75\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(12L, manager.dbSize());
        assertEquals(List.of(30L), manager.process.waits);
        manager.assertClean();
    }

    @Test
    public void invalidNullOrExcessiveSizeOutputIsNotParsedAsSuccess() throws Exception {
        for (String value : List.of("NULL\n", "", "x".repeat(129))) {
            TestManager manager = manager();
            manager.bytes = value.getBytes(StandardCharsets.UTF_8);
            try {
                manager.dbSize();
                fail("Expected size failure");
            } catch (IOException expected) {
                assertEquals("Database size unavailable", expected.getMessage());
                assertNull(expected.getCause());
            }
            manager.assertClean();
        }
    }

    @Test
    public void failedSizeCommandCannotUseNumericStdout() throws Exception {
        TestManager manager = manager();
        manager.bytes = "12.75\n".getBytes(StandardCharsets.UTF_8);
        manager.process.exitCode = 1;
        try {
            manager.dbSize();
            fail("Expected size failure");
        } catch (IOException expected) {
            assertEquals("Database size unavailable", expected.getMessage());
        }
        manager.assertClean();
    }

    @Test
    public void posixSpoolIsPrivateBeforeAnyOutput() throws Exception {
        assumeTrue(Files.getFileStore(folder.getRoot().toPath()).supportsFileAttributeView("posix"));
        Path file = new HaConfigManager(new HaConfigSettingsTest.StubHaConfigSettings("mysql")).createDatabaseTempFile();
        try {
            assertEquals(0, Files.size(file));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(file.getParent()));
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
        }
    }

    @Test
    public void nonPosixStorageFailsClosed() throws Exception {
        assumeFalse(Files.getFileStore(folder.getRoot().toPath()).supportsFileAttributeView("posix"));
        try {
            new HaConfigManager(new HaConfigSettingsTest.StubHaConfigSettings("mysql")).createDatabaseTempFile();
            fail("Expected unavailable private storage");
        } catch (IOException expected) {
            assertEquals("Private database temporary storage unavailable", expected.getMessage());
        }
    }

    private TestManager manager() {
        return new TestManager(folder.getRoot().toPath());
    }

    private void assertDumpFailure(TestManager manager, TestRequest request) throws Exception {
        expectDumpFailure(manager, request);
        assertEquals(0, request.httpTouches);
        manager.assertClean();
    }

    private void expectDumpFailure(TestManager manager, ApiRequest request) throws Exception {
        try {
            manager.getLinkInternal("haConfig", "haConfig", "dbdump", request);
            fail("Expected dump failure");
        } catch (ClientVisibleException expected) {
            assertEquals(500, expected.getStatus());
            assertEquals("DumpFailed", expected.getCode());
            assertEquals("Failed to create database dump", expected.getMessage());
            assertNull(expected.getDetail());
            assertNull(expected.getCause());
            assertFalse(expected.toString().contains(PRIVATE));
        }
    }

    private static ServletOutputStream output(ByteArrayOutputStream bytes, boolean fail) {
        return new ServletOutputStream() {
            @Override public boolean isReady() { return true; }
            @Override public void setWriteListener(WriteListener listener) { }
            @Override public void write(int value) throws IOException {
                if (fail) throw new IOException(PRIVATE);
                bytes.write(value);
            }
        };
    }

    private static class TestRequest extends ApiRequest {
        final ResponseRecorder response = new ResponseRecorder();
        int httpTouches;
        Runnable beforeHttp;
        RuntimeException callbackFailure;

        TestRequest() { super(null, null); }

        @Override public ApiServletContext getServletContext() {
            httpTouches++;
            if (beforeHttp != null) beforeHttp.run();
            if (callbackFailure != null) throw callbackFailure;
            return new ApiServletContext(null, response.proxy, null);
        }
    }

    private static class ResponseRecorder {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final Map<String, String> headers = new HashMap<>();
        String contentType;
        boolean fail;
        final HttpServletResponse proxy = (HttpServletResponse) Proxy.newProxyInstance(
                HttpServletResponse.class.getClassLoader(), new Class<?>[] {HttpServletResponse.class},
                (target, method, args) -> {
                    switch (method.getName()) {
                        case "setHeader": headers.put((String) args[0], (String) args[1]); return null;
                        case "setContentType": contentType = (String) args[0]; return null;
                        case "getOutputStream": return output(bytes, fail);
                        default: throw new AssertionError("Unexpected response method " + method.getName());
                    }
                });
    }

    private static class TestManager extends HaConfigManager {
        final Path root;
        final FakeProcess process = new FakeProcess();
        Path spool;
        ProcessBuilder builder;
        byte[] bytes = SQL;
        String childPassword;
        IOException launchFailure;
        boolean removeOutput, tempFailure;

        TestManager(Path root) {
            super(new HaConfigSettingsTest.StubHaConfigSettings("mysql"));
            this.root = root;
        }

        @Override protected Path createDatabaseTempFile() throws IOException {
            if (tempFailure) throw new IOException(PRIVATE);
            // Synthetic test bytes only; real permission creation is covered
            // separately, and the production method fails closed on Windows.
            spool = Files.createFile(Files.createTempDirectory(root, "case-").resolve("output"));
            return spool;
        }

        @Override protected Process startDatabaseProcess(ProcessBuilder value) throws IOException {
            builder = value;
            childPassword = value.environment().get("MYSQL_PWD");
            if (launchFailure != null) throw launchFailure;
            assertEquals(spool.toFile(), builder.redirectOutput().file());
            assertEquals(ProcessBuilder.Redirect.DISCARD, builder.redirectError());
            Files.write(spool, bytes);
            if (removeOutput) Files.delete(spool);
            return process;
        }

        void assertClean() {
            if (spool != null) {
                assertFalse(Files.exists(spool));
                assertFalse(Files.exists(spool.getParent()));
            }
        }
    }

    private static class FakeProcess extends Process {
        final List<Long> waits = new ArrayList<>();
        final OutputStream stdin = new ByteArrayOutputStream();
        boolean completed = true, alive, forceRequired, destroyed, forced, interrupt;
        int exitCode;
        @Override public OutputStream getOutputStream() { return stdin; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(PRIVATE.getBytes(StandardCharsets.UTF_8)); }
        @Override public int waitFor() { return exitCode; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            waits.add(timeout);
            if (interrupt) {
                interrupt = false;
                throw new InterruptedException(PRIVATE);
            }
            if (timeout > 2) return completed;
            return !alive;
        }
        @Override public int exitValue() { return exitCode; }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() { destroyed = true; alive = forceRequired; }
        @Override public Process destroyForcibly() { forced = true; alive = false; return this; }
    }
}
