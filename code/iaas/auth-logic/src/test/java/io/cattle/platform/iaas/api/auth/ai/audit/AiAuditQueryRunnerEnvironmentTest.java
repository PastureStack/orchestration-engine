package io.cattle.platform.iaas.api.auth.ai.audit;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Actual fresh JVM environment, without mutating System.getenv or launching the native owner. */
public class AiAuditQueryRunnerEnvironmentTest {
    static final String UPSTREAM = "PASTURESTACK_CONSOLE_UPSTREAM_URL";

    @Test public void fixedOwnerReceivesOnlyExactTrustedOperatorUpstream() throws Exception {
        probe("http://127.0.0.1:8083");
    }

    @Test public void absentOrEmptyOperatorValuePreservesNativeFallbackWithoutInheritedCredentials() throws Exception {
        probe(null); probe("");
    }

    @Test public void localOwnerTopologyIsRebuiltFromFixedCoordinatesAndNumericPort() throws Exception {
        assertNull(AiAuditQueryRunner.FixedProcess.localUpstream(null));
        assertEquals("", AiAuditQueryRunner.FixedProcess.localUpstream(""));
        Map<String, String> expected = Map.of(
                "HTTP://LOCALHOST", "http://localhost:80",
                "https://127.0.0.1/", "https://127.0.0.1:443",
                "http://[::1]:8083/", "http://[::1]:8083",
                "HTTPS://[::1]", "https://[::1]:443",
                "http://localhost:1", "http://localhost:1",
                "http://127.0.0.1:65535", "http://127.0.0.1:65535");
        for (var entry : expected.entrySet()) assertEquals(entry.getValue(), AiAuditQueryRunner.FixedProcess.localUpstream(entry.getKey()));
    }

    @Test public void nonlocalOrAmbiguousOwnerTopologyFailsClosedWithoutEchoingInput() {
        for (String input : List.of("http://outside.invalid:8083", "file://localhost", "ftp://localhost", "//localhost:8083",
                "http://user:PRIVATE@localhost:8083", "http://localhost/path", "http://localhost//", "http://localhost/%2F",
                "http://localhost?PRIVATE", "http://localhost?", "http://localhost#PRIVATE", "http://localhost#",
                "http://127.0.0.2", "http://localhost.", "http://%6cocalhost", "http://[::ffff:127.0.0.1]",
                "http://localhost:0", "http://localhost:65536", "http://localhost:", " http://localhost", "http://localhost/\r\nPRIVATE")) {
            IOException failure = assertThrows(IOException.class, () -> AiAuditQueryRunner.FixedProcess.localUpstream(input));
            assertEquals("Audit owner topology unavailable", failure.getMessage()); assertNull(failure.getCause());
            assertFalse(failure.toString().contains(input));
        }
    }

    private static void probe(String upstream) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder child = new ProcessBuilder(java, "-cp", classpath, EnvironmentProbe.class.getName());
        child.environment().put("HTTP_PROXY", "PRIVATE-PROXY-CANARY");
        child.environment().put("Authorization", "PRIVATE-AUTH-CANARY");
        child.environment().put("Cookie", "PRIVATE-COOKIE-CANARY");
        child.environment().put("JWT", "PRIVATE-JWT-CANARY");
        child.environment().put("PASTURESTACK_CONSOLE_SESSION_DIAL_URL", "http://unrelated.invalid");
        if (upstream == null) child.environment().remove(UPSTREAM); else child.environment().put(UPSTREAM, upstream);
        child.redirectErrorStream(true);
        Process process = child.start();
        try {
            assertTrue("environment probe deadline", process.waitFor(20, TimeUnit.SECONDS));
            String output = new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8);
            assertEquals(output, 0, process.exitValue());
            assertEquals("PASS_FIXED_PRIVATE_OWNER_ENVIRONMENT", output.trim());
            for (String secret : List.of("PRIVATE-PROXY-CANARY", "PRIVATE-AUTH-CANARY", "PRIVATE-COOKIE-CANARY", "PRIVATE-JWT-CANARY"))
                assertFalse(output.contains(secret));
        } finally { process.destroyForcibly(); }
    }

    public static final class EnvironmentProbe {
        public static void main(String[] arguments) throws Exception {
            ProcessBuilder actual = AiAuditQueryRunner.FixedProcess.processBuilder();
            Map<String, String> expected = new LinkedHashMap<>(Map.of("PATH", "/usr/bin:/bin", "LANG", "C.UTF-8"));
            String upstream = System.getenv(UPSTREAM);
            if (upstream != null) expected.put(UPSTREAM, upstream);
            if (!actual.environment().equals(expected)) throw new AssertionError("Fixed owner environment differs from the exact allowlist");
            if (!actual.command().equals(List.of("/usr/bin/pasturestack-console-broker", "--ai-audit-query")))
                throw new AssertionError("Fixed executable or argv changed");
            if (!actual.directory().equals(new java.io.File("/")) || actual.redirectError() != ProcessBuilder.Redirect.DISCARD)
                throw new AssertionError("Fixed private process boundary changed");
            System.out.println("PASS_FIXED_PRIVATE_OWNER_ENVIRONMENT");
        }
    }
}
