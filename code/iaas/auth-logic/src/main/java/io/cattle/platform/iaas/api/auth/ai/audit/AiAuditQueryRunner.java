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
        @Override public byte[] run(byte[] input) throws Exception {
            ProcessBuilder builder = new ProcessBuilder("/usr/bin/pasturestack-console-broker", "--ai-audit-query");
            builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin");
            builder.environment().put("LANG", "C.UTF-8"); builder.directory(new java.io.File("/"));
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
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
