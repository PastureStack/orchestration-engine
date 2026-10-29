package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.json.JsonMapper;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Fixed Compose owner IPC; no caller-controlled executable, path, arguments or inherited credentials. */
public class AiCatalogLaunchRunner {
    static final int OUTPUT_BYTES = 8 * 1024 * 1024;
    @Inject JsonMapper json;
    interface Executor { byte[] run(byte[] input) throws Exception; }
    final Executor executor;
    public AiCatalogLaunchRunner() { executor = new FixedProcess(); }
    AiCatalogLaunchRunner(Executor executor) { this.executor = executor; }

    public Map<String, Object> run(Map<String, Object> input) {
        boolean finalize = "finalize".equals(input.get("phase"));
        try {
            byte[] bytes = json.writeValueAsString(input).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > (finalize ? 10 * 1024 * 1024 : 1536 * 1024)) throw error(422, "CatalogLaunchUnsupported");
            byte[] output = executor.run(bytes);
            if (output == null || output.length > OUTPUT_BYTES) throw error(503, "CatalogLaunchUnavailable");
            Map<String, Object> result = json.readValue(output);
            if (!(result.get("formatVersion") instanceof Number version) || version.doubleValue() != 1D
                    || !(finalize ? "finalize" : "inspect").equals(result.get("phase"))) throw error(503, "CatalogLaunchUnavailable");
            return result;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw error(503, "CatalogLaunchUnavailable"); }
        catch (io.github.ibuildthecloud.gdapi.exception.ClientVisibleException bounded) { throw bounded; }
        catch (Exception unavailable) { throw error(503, "CatalogLaunchUnavailable"); }
    }

    static class FixedProcess implements Executor {
        @Override public byte[] run(byte[] input) throws Exception {
            ProcessBuilder builder = new ProcessBuilder("/usr/bin/compose-executor.real", "--catalog-launch-preview");
            builder.environment().clear(); builder.environment().put("PATH", "/usr/bin:/bin"); builder.environment().put("LANG", "C.UTF-8");
            builder.directory(new java.io.File("/")); builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process = builder.start();
            try {
                CompletableFuture<byte[]> output = CompletableFuture.supplyAsync(() -> {
                    try (InputStream stream = process.getInputStream()) {
                        byte[] result = stream.readNBytes(OUTPUT_BYTES + 1);
                        if (result.length > OUTPUT_BYTES) { process.destroyForcibly(); throw new IllegalStateException("Bounded owner output required"); }
                        return result;
                    } catch (IOException failure) { throw new IllegalStateException("Owner IPC unavailable"); }
                });
                CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                    try (var stdin = process.getOutputStream()) { stdin.write(input); }
                    catch (IOException failure) { throw new IllegalStateException("Owner IPC unavailable"); }
                });
                if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("Owner IPC deadline");
                writer.get(1, TimeUnit.SECONDS);
                if (process.exitValue() != 0) throw error(422, "CatalogLaunchUnsupported");
                return output.get(1, TimeUnit.SECONDS);
            } finally { process.destroyForcibly(); }
        }
    }
}
