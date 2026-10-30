package io.cattle.platform.iaas.api.auditing;

import io.github.ibuildthecloud.gdapi.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Small durable, bounded, metadata-only outbox. Delivery is at least once;
 * eventId is retained on retries so consumers can identify a repeated event.
 * A full or unwritable outbox rejects admission instead of dropping events.
 */
public final class ApiKeyAuditOutbox {
    private static final Logger LOG = LoggerFactory.getLogger(ApiKeyAuditOutbox.class);
    static final int MAX_EVENTS = 10000;
    static final long MAX_BYTES = 64L * 1024 * 1024;
    static final int MAX_EVENT_BYTES = 8192;
    static final int REPLAY_BATCH = 16;

    private final ApiKeyAuditDirectory directory;
    private final JsonMapper mapper;
    private final int maxEvents;
    private final long maxBytes;
    // This outbox instance belongs to one server. Reserve bounded room for the
    // response of every admitted request so new decisions cannot crowd it out.
    // After a server crash, durable decision events remain explicitly PENDING;
    // no response/completion is invented for an interrupted request.
    private final Map<String, Boolean> responseReservations = new HashMap<>();
    private long lastDeliveryWarning = Long.MIN_VALUE;

    public ApiKeyAuditOutbox(Path directory, JsonMapper mapper) {
        this(directory, mapper, MAX_EVENTS, MAX_BYTES);
    }

    ApiKeyAuditOutbox(Path directory, JsonMapper mapper, int maxEvents, long maxBytes) {
        this.directory = new ApiKeyAuditDirectory(directory);
        this.mapper = mapper;
        this.maxEvents = maxEvents;
        this.maxBytes = maxBytes;
    }

    public synchronized void persist(Map<String, Object> event, Consumer<Map<String, Object>> deliver) {
        try (ApiKeyAuditDirectory.Access files = directory.open(true)) {
            try (FileChannel lockChannel = files.openFile(files.leaf("outbox.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {
                replay(files, deliver);
                Path terminalPath = null;
                Object terminal = event.get("terminalEventId");
                Object delegated = event.get("delegatedEventId");
                if (terminal != null && delegated != null && !terminal.equals(delegated))
                    throw new IOException("Conflicting terminal event identity");
                Object identity = terminal == null ? delegated : terminal; // Backward-compatible delegated alias.
                if (identity != null && !(identity instanceof String)) throw new IOException("Invalid terminal event identity");
                if (identity instanceof String terminalId) {
                    if (!terminalId.matches("[a-f0-9]{64}")) throw new IOException("Invalid terminal event identity");
                    terminalPath = files.leaf("terminal-" + terminalId + ".json");
                    if (files.exists(terminalPath)) {
                        // The first queued terminal result is immutable even
                        // while the DB is offline and after a server restart.
                        files.force();
                        return;
                    }
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                mapper.writeValue(output, event);
                byte[] bytes = output.toByteArray();
                if (bytes.length > MAX_EVENT_BYTES) {
                    throw new IOException("API-key audit event exceeds metadata bound");
                }
                List<Path> pending = files.pending(MAX_EVENTS);
                long usedBytes = 0;
                for (Path path : pending) {
                    usedBytes += files.size(path);
                }
                Map<?, ?> data = event.get("data") instanceof Map<?, ?> values ? values : Map.of();
                String requestId = data.get("requestId") instanceof String id ? id : null;
                boolean decision = "decision".equals(data.get("phase")) && requestId != null;
                boolean response = "response".equals(data.get("phase")) && requestId != null;
                boolean hasReservation = response && responseReservations.containsKey(requestId);
                int newReservation = decision && !responseReservations.containsKey(requestId) ? 1 : 0;
                int reserved = responseReservations.size() - (hasReservation ? 1 : 0) + newReservation;
                if (pending.size() + 1 + reserved > maxEvents
                        || usedBytes + bytes.length + (long) reserved * MAX_EVENT_BYTES > maxBytes) {
                    throw new IOException("API-key audit outbox is full");
                }
                // CREATE_NEW plus force means a successful admission has a durable
                // record even if DB delivery or the process fails immediately.
                String fileName = System.currentTimeMillis() + "-" + UUID.randomUUID();
                Path path = terminalPath == null ? files.leaf(fileName + ".json") : terminalPath;
                // One unacknowledged temporary file is replaceable after a crash;
                // repeated interrupted admissions cannot grow abandoned files.
                Path temporary = files.leaf("outbox.pending");
                if (files.exists(temporary)) files.delete(temporary);
                try (FileChannel channel = files.openFile(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) {
                        channel.write(buffer);
                    }
                    channel.force(true);
                }
                files.move(temporary, path);
                files.force();
                if (decision) {
                    responseReservations.put(requestId, Boolean.TRUE);
                } else if (hasReservation) {
                    responseReservations.remove(requestId);
                }
                replay(files, deliver);
            }
        } catch (IOException | RuntimeException error) {
            // Do not attach the original exception: JDBC errors can include values.
            throw new IllegalStateException("API-key audit persistence is unavailable");
        }
    }

    public synchronized void replayPending(Consumer<Map<String, Object>> deliver) {
        ApiKeyAuditDirectory.Access access;
        try {
            access = directory.open(false);
        } catch (java.nio.file.NoSuchFileException absent) {
            return; // An unused outbox need not exist yet.
        } catch (IOException | RuntimeException unavailable) {
            throw new IllegalStateException("API-key audit replay is unavailable");
        }
        try (ApiKeyAuditDirectory.Access files = access;
             FileChannel lockChannel = files.openFile(files.leaf("outbox.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = lockChannel.lock()) {
            replay(files, deliver);
        } catch (IOException | RuntimeException unavailable) {
            throw new IllegalStateException("API-key audit replay is unavailable");
        }
    }

    private void replay(ApiKeyAuditDirectory.Access files, Consumer<Map<String, Object>> deliver) throws IOException {
        // A successful SQL statement can still roll back with the caller's
        // Core transaction. Keep both old and new events durable until the
        // independent replayer can deliver without joining that transaction.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        List<Path> paths = files.pending(MAX_EVENTS);
        int processed = 0;
        for (Path path : paths) {
            if (processed++ >= REPLAY_BATCH) {
                break;
            }
            if (files.size(path) > MAX_EVENT_BYTES) {
                throw new IOException("Invalid API-key audit outbox event");
            }
            Map<?, ?> raw = mapper.readValue(files.read(path, MAX_EVENT_BYTES), Map.class);
            Map<String, Object> event = new java.util.HashMap<>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                event.put(String.class.cast(entry.getKey()), entry.getValue());
            }
            try {
                deliver.accept(event);
            } catch (RuntimeException unavailable) {
                // Already durable. Retry a bounded batch on the next request.
                long now = System.nanoTime();
                if (lastDeliveryWarning == Long.MIN_VALUE || now - lastDeliveryWarning > TimeUnit.MINUTES.toNanos(1)) {
                    LOG.warn("API-key audit database delivery is unavailable; durable outbox retry is pending");
                    lastDeliveryWarning = now;
                }
                return;
            }
            files.delete(path);
            files.force();
        }
    }
}
