package io.cattle.platform.iaas.api.auth.ai.audit;

import io.cattle.platform.json.JsonMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Private, bounded IPC to the installed read-only audit owner. No executable/URL comes from input. */
public class AiAuditQueryRunner {
    static final int MAX_OUTPUT = 512 * 1024;
    static final Semaphore SLOTS = new Semaphore(2);
    @Inject JsonMapper json;
    interface Executor { byte[] run(byte[] input) throws Exception; }
    final Executor executor;
    public AiAuditQueryRunner() { this.executor = new FixedProcess(); }
    AiAuditQueryRunner(Executor executor) { this.executor = executor; }

    Map<String, Object> run(Map<String, Object> input) throws Exception {
        if (!SLOTS.tryAcquire()) throw new IOException("Audit query capacity");
        try {
            byte[] bytes = json.writeValueAsBytes(input);
            if (bytes.length > 64 * 1024) throw new IOException("Audit query input bound");
            byte[] output = executor.run(bytes);
            if (output == null || output.length > MAX_OUTPUT) throw new IOException("Audit query output bound");
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(output);
            return json.readValue(output);
        } finally { SLOTS.release(); }
    }

    static final class FixedProcess implements Executor {
        static String localUpstream(String value) throws IOException {
            if (value == null) return null;
            if (value.isEmpty()) return ""; // The native owner retains its existing empty/unset fallback.
            java.net.URI parsed;
            try { parsed = new java.net.URI(value); }
            catch (java.net.URISyntaxException invalid) { throw topologyUnavailable(); }
            boolean secure = "https".equalsIgnoreCase(parsed.getScheme());
            if (!secure && !"http".equalsIgnoreCase(parsed.getScheme())) throw topologyUnavailable();
            String path = parsed.getRawPath();
            if (parsed.getRawUserInfo() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null
                    || path == null || !(path.isEmpty() || "/".equals(path))) throw topologyUnavailable();
            String host = parsed.getHost();
            String authorityHost;
            String prefix;
            if ("127.0.0.1".equals(host)) {
                authorityHost = "127.0.0.1";
                prefix = secure ? "https://127.0.0.1:" : "http://127.0.0.1:";
            } else if ("localhost".equalsIgnoreCase(host)) {
                authorityHost = "localhost";
                prefix = secure ? "https://localhost:" : "http://localhost:";
            } else if ("[::1]".equals(host) || "::1".equals(host)) {
                authorityHost = "[::1]";
                prefix = secure ? "https://[::1]:" : "http://[::1]:";
            } else throw topologyUnavailable();
            int port = parsed.getPort();
            if (port == -1) {
                if (!authorityHost.equalsIgnoreCase(parsed.getRawAuthority())) throw topologyUnavailable();
                port = secure ? 443 : 80;
            }
            if (port < 1 || port > 65535) throw topologyUnavailable();
            // Only fixed scheme/host literals and a bounded integer reach the environment.
            return prefix + port;
        }

        private static IOException topologyUnavailable() { return new IOException("Audit owner topology unavailable"); }

        static ProcessBuilder processBuilder() throws IOException {
            ProcessBuilder builder = new ProcessBuilder("/usr/bin/pasturestack-console-broker", "--ai-audit-query");
            builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin");
            builder.environment().put("LANG", "C.UTF-8"); builder.directory(new java.io.File("/"));
            // Operator topology only. Caller credentials remain private stdin.
            String upstream = localUpstream(System.getenv("PASTURESTACK_CONSOLE_UPSTREAM_URL"));
            if (upstream != null) builder.environment().put("PASTURESTACK_CONSOLE_UPSTREAM_URL", upstream);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            return builder;
        }

        @Override public byte[] run(byte[] input) throws Exception {
            ProcessBuilder builder = processBuilder();
            Process process = builder.start();
            try {
                CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                    try (var stream = process.getInputStream()) {
                        byte[] result = stream.readNBytes(MAX_OUTPUT + 1);
                        if (result.length > MAX_OUTPUT) { process.destroyForcibly(); throw new IllegalStateException("Audit output bound"); }
                        return result;
                    } catch (IOException unavailable) { throw new IllegalStateException("Audit IPC unavailable"); }
                });
                CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                    try (var stdin = process.getOutputStream()) { stdin.write(input); }
                    catch (IOException unavailable) { throw new IllegalStateException("Audit IPC unavailable"); }
                });
                if (!process.waitFor(45, TimeUnit.SECONDS)) throw new IOException("Audit IPC deadline");
                writer.get(1, TimeUnit.SECONDS);
                if (process.exitValue() != 0) throw new IOException("Audit IPC unsuccessful");
                return output.get(1, TimeUnit.SECONDS);
            } finally { process.destroyForcibly(); }
        }
    }
}
