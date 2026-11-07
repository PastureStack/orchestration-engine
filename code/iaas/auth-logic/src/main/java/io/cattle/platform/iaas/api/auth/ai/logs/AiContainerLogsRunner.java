package io.cattle.platform.iaas.api.auth.ai.logs;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cattle.platform.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** Fixed broker owner via private stdin. No caller executable, URL, environment or shell. */
public class AiContainerLogsRunner {
    static final int MAX_OUTPUT = 512 * 1024;
    private static final Semaphore SLOTS = new Semaphore(2);
    private static final ObjectReader RESULT_READER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readerFor(Map.class);
    @Inject JsonMapper json;
    interface Executor { byte[] run(byte[] input, long deadline) throws Exception; }
    private final Executor executor;
    public AiContainerLogsRunner() { executor = new FixedProcess(); }
    AiContainerLogsRunner(Executor executor) { this.executor = executor; }

    Map<String, Object> read(AiContainerLogsTicket ticket, ApiRequest request, long actor, long project, String uuid) throws Exception {
        if (!SLOTS.tryAcquire()) throw new IOException("Bounded logs capacity");
        try {
            Map<String, Object> input = ticket.consume(request, actor, project, uuid);
            byte[] bytes = json.writeValueAsBytes(input);
            if (bytes.length > 65536) throw new IOException("Bounded logs input");
            byte[] output = executor.run(bytes, ((Number) input.get("expiresAtMillis")).longValue());
            if (output == null || output.length > MAX_OUTPUT) throw new IOException("Bounded logs output");
            // The validated parser also supplies the disclosed values; never reinterpret the bytes.
            Map<String, Object> result = RESULT_READER.readValue(output);
            if (result == null) throw new IOException("Bounded logs output");
            return result;
        } finally { SLOTS.release(); }
    }

    static final class FixedProcess implements Executor {
        @Override public byte[] run(byte[] input, long deadline) throws Exception {
            ProcessBuilder builder = new ProcessBuilder("/usr/bin/pasturestack-console-broker", "--ai-container-logs");
            builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin");
            builder.environment().put("LANG", "C.UTF-8"); builder.directory(new java.io.File("/"));
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            try {
                var output = CompletableFuture.supplyAsync(() -> {
                    try (var stream = process.getInputStream()) {
                        byte[] result = stream.readNBytes(MAX_OUTPUT + 1);
                        if (result.length > MAX_OUTPUT) { process.destroyForcibly(); throw new IllegalStateException("Bounded logs output"); }
                        return result;
                    } catch (IOException unavailable) { throw new IllegalStateException("Bounded logs IPC"); }
                });
                var writer = CompletableFuture.runAsync(() -> {
                    try (var stream = process.getOutputStream()) { stream.write(input); }
                    catch (IOException unavailable) { throw new IllegalStateException("Bounded logs IPC"); }
                });
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0 || !process.waitFor(remaining, TimeUnit.MILLISECONDS)) throw new IOException("Bounded logs deadline");
                writer.get(100, TimeUnit.MILLISECONDS);
                if (process.exitValue() != 0) throw new IOException("Bounded logs unavailable");
                return output.get(100, TimeUnit.MILLISECONDS);
            } finally { process.destroyForcibly(); }
        }
    }
}
