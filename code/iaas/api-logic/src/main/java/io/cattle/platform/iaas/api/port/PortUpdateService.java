package io.cattle.platform.iaas.api.port;

import static io.cattle.platform.core.model.tables.HostTable.HOST;
import static io.cattle.platform.core.model.tables.InstanceHostMapTable.INSTANCE_HOST_MAP;
import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.PortTable.PORT;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.constants.PortConstants;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Port;
import io.cattle.platform.core.util.PortBindingAddress;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.Lock;
import io.cattle.platform.lock.definition.AbstractBlockingLockDefintion;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.jooq.Record;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Shared native Port.publicPort save owner. Locks precede SQL and remain held through commit. */
public class PortUpdateService extends AbstractJooqDao {
    public static final String BEFORE_HEADER = "X-PastureStack-Port-Before";
    private static final String EXPECTED = "native.port.update.expected";
    private enum Required { BEFORE }
    private static final String REQUIRED = "native.port.update.required";
    private static final String SCOPE = "native.port.update.scope";
    private record Expected(Map<String, Object> value) { }
    private record Scope(long portId, long instanceId, Integer requested, List<Long> hostIds,
                         java.util.concurrent.atomic.AtomicBoolean active) { }
    private static final Set<String> BEFORE_FIELDS = Set.of("uuid", "sourceUuid", "accountId", "instanceId",
            "publicPort", "privatePort", "protocol", "bindAddress");
    private final Set<String> quarantinedLocks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Inject LockManager locks;
    @Inject @Named("CoreTransactionManager") PlatformTransactionManager transactions;
    @Inject ObjectManager objects;
    @Inject JsonMapper json;

    public static Map<String, Object> before(Port port, Instance source) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("uuid", port.getUuid()); value.put("sourceUuid", source.getUuid());
        value.put("accountId", port.getAccountId()); value.put("instanceId", port.getInstanceId());
        value.put("publicPort", port.getPublicPort()); value.put("privatePort", port.getPrivatePort());
        value.put("protocol", port.getProtocol()); value.put("bindAddress", DataAccessor.fieldString(port, "bindAddress"));
        return value;
    }

    /** Only trusted producers require CAS; legacy native callers retain unconditional PUT semantics. */
    public static void requireBefore(ApiRequest request) {
        request.setAttribute(REQUIRED, Required.BEFORE);
    }

    /** A typed domain request binds the same native precondition as the human UI header. */
    public static void attachBefore(ApiRequest request, Port port, Instance source) {
        requireBefore(request);
        if (port == null || source == null) throw fail(428, "PortUpdatePreconditionRequired");
        request.setAttribute(EXPECTED, new Expected(before(port, source)));
    }

    public static void transferScope(ApiRequest original, ApiRequest domain) {
        Object scope = original.getAttribute(SCOPE);
        if (scope instanceof Scope owned && owned.active().get()) domain.setAttribute(SCOPE, owned);
    }

    private static final class NativeLock extends AbstractBlockingLockDefintion {
        NativeLock(String key) { super(key); }
    }

    private NativeLock nativeLock(String key) {
        if (quarantinedLocks.contains(key)) throw fail(503, "PortUpdateLockUnavailable");
        return new NativeLock(key);
    }

    public <T> T withLocks(ApiRequest request, Port seed, Integer requested, Supplier<T> action) {
        if (seed == null || seed.getInstanceId() == null || !Set.of("tcp", "udp").contains(Objects.toString(seed.getProtocol(), "")))
            throw fail(422, "PortUpdateUnsupported");
        Object known = request.getAttribute(SCOPE);
        if (known instanceof Scope scope && scope.active().get() && TransactionSynchronizationManager.isActualTransactionActive()) {
            if (scope.portId() != seed.getId() || scope.instanceId() != seed.getInstanceId() || !Objects.equals(scope.requested(), requested))
                throw fail(409, "PortUpdateConflict");
            return action.get();
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) throw fail(503, "PortUpdateTransactionRequired");
            List<Lock> held = new ArrayList<>();
            try {
                if (requested != null) {
                    Lock lock = locks.getLockProvider().getLock(nativeLock(
                            PortConstants.ALLOCATION_PORT_LOCK_PREFIX + seed.getProtocol() + "." + requested));
                    lock.lock(); held.add(lock);
                }
                Lock instance = locks.getLockProvider().getLock(nativeLock(PortConstants.INSTANCE_PORTS_LOCK_PREFIX + seed.getInstanceId()));
                instance.lock(); held.add(instance);
                Scope scope = lockHostScope(request, seed, requested);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCompletion(int status) {
                        scope.active().set(false);
                        release(held);
                    }
                });
            } catch (RuntimeException | Error failure) {
                try { release(held); }
                catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
                throw failure;
            }
            return action.get(); // ApiDelegate owns this transaction; release only after its final commit/rollback.
        }
        // Standalone callers use the same checked lease release after their own final commit/rollback.
        return new TransactionTemplate(transactions).execute(status -> withLocks(request, seed, requested, action));
    }

    private void release(List<Lock> held) {
        Throwable failure = null;
        for (int i = held.size() - 1; i >= 0; i--) {
            Lock lock = held.get(i);
            try { lock.unlockChecked(); }
            catch (RuntimeException | Error error) {
                quarantinedLocks.add(lock.getLockDefinition().getLockId());
                if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
                continue; // Retain this exact provider lease; a still-held mutex must never be replaced under the same key.
            }
            try { locks.getLockProvider().releaseLock(lock); }
            catch (RuntimeException | Error error) {
                quarantinedLocks.add(lock.getLockDefinition().getLockId());
                if (failure == null) failure = error; else if (failure != error) failure.addSuppressed(error);
            }
        }
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
    }

    public Port seed(long id, long account) {
        Port port = objects.loadResource(Port.class, id);
        if (port == null || !Objects.equals(port.getAccountId(), account)) throw fail(404, "NotFound");
        return port;
    }

    public Object update(ApiRequest request, long id, Object value, Supplier<Object> save) {
        Integer requested = publicPort(value);
        long account = ApiUtils.getPolicy().getAccountId();
        Port seed = seed(id, account);
        return withLocks(request, seed, requested, () -> {
            Port port = currentPort(id);
            if (!Objects.equals(seed.getUuid(), port.getUuid()) || !Objects.equals(seed.getInstanceId(), port.getInstanceId()))
                throw fail(409, "PortUpdateConflict");
            Instance source = currentSource(request, port);
            requireOwned(port, source, account);
            Map<String, Object> expected = expected(request);
            if (expected != null && !expected.equals(before(port, source))) throw fail(409, "PortUpdateConflict");
            validate(request, port, source, requested);
            // The original filtered manager retains native schema/RBAC/Key checks, persistence and UPDATE scheduling.
            return save.get();
        });
    }

    /** Preview is advisory; no port is reserved after this caller releases its native locks. */
    public void preview(ApiRequest request, long id, long account, Object requested) {
        Port port = currentPort(id); Instance source = currentSource(request, port);
        requireOwned(port, source, account); validate(request, port, source, publicPort(requested));
    }

    private Scope lockHostScope(ApiRequest request, Port seed, Integer requested) {
        TransactionTemplate read = new TransactionTemplate(transactions);
        read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); read.setReadOnly(true);
        List<Long> hosts = read.execute(status -> create().select(INSTANCE_HOST_MAP.HOST_ID).from(INSTANCE_HOST_MAP)
                .where(INSTANCE_HOST_MAP.INSTANCE_ID.eq(seed.getInstanceId()).and(INSTANCE_HOST_MAP.REMOVED.isNull())
                    .and(INSTANCE_HOST_MAP.HOST_ID.isNotNull())).orderBy(INSTANCE_HOST_MAP.HOST_ID).fetch(INSTANCE_HOST_MAP.HOST_ID));
        // Host rows are database-wide sentinels, so independent Engine processes use the same lock order too.
        // These precede every Port/Instance locking read; never discover an extra Host after taking child rows.
        if (!hosts.isEmpty()) {
            var physical = create().select(HOST.PHYSICAL_HOST_ID).from(HOST)
                    .where(HOST.ID.in(hosts).and(HOST.PHYSICAL_HOST_ID.isNotNull()));
            create().select(HOST.ID).from(HOST).where(HOST.ID.in(hosts).or(HOST.PHYSICAL_HOST_ID.in(physical)))
                    .orderBy(HOST.ID).forUpdate().fetch();
        }
        Scope scope = new Scope(seed.getId(), seed.getInstanceId(), requested, List.copyOf(hosts), new java.util.concurrent.atomic.AtomicBoolean(true));
        request.setAttribute(SCOPE, scope); return scope;
    }

    private Port currentPort(long id) {
        create().connection(connection -> { if (connection.getAutoCommit()) throw fail(503, "PortUpdateTransactionRequired"); });
        Port port = create().selectFrom(PORT).where(PORT.ID.eq(id)).forUpdate().fetchOne();
        if (port == null) throw fail(404, "NotFound");
        return port;
    }

    private Instance currentSource(ApiRequest request, Port port) {
        if (port.getInstanceId() == null || !(request.getAttribute(SCOPE) instanceof Scope scope) || !scope.active().get()
                || scope.portId() != port.getId() || scope.instanceId() != port.getInstanceId()) throw fail(409, "PortUpdateConflict");
        Instance source = create().selectFrom(INSTANCE).where(INSTANCE.ID.eq(port.getInstanceId())).forUpdate().fetchOne();
        if (source == null) throw fail(409, "PortUpdateConflict");
        return source;
    }

    private static void requireOwned(Port port, Instance source, long account) {
        if (port.getRemoved() != null || source.getRemoved() != null || !Objects.equals(port.getAccountId(), account)
                || !Objects.equals(source.getAccountId(), account) || port.getUuid() == null || source.getUuid() == null)
            throw fail(409, "PortUpdateConflict");
        if (!Set.of("active", "inactive").contains(Objects.toString(port.getState(), ""))
                || !Set.of("running", "stopped").contains(Objects.toString(source.getState(), ""))) throw fail(409, "PortUpdateBusy");
    }

    private void validate(ApiRequest request, Port selected, Instance source, Integer requested) {
        if (requested == null) return;
        String mode = DataAccessor.fieldString(source, "networkMode");
        // Host networking publishes the application's private port, not a configurable NAT public port.
        if ("host".equalsIgnoreCase(mode)) {
            if (!requested.equals(selected.getPrivatePort())) throw fail(422, "PortUnavailable");
            return;
        }
        List<Long> hostIds = create().select(INSTANCE_HOST_MAP.HOST_ID).from(INSTANCE_HOST_MAP)
                .join(HOST).on(HOST.ID.eq(INSTANCE_HOST_MAP.HOST_ID))
                .where(INSTANCE_HOST_MAP.INSTANCE_ID.eq(source.getId()).and(INSTANCE_HOST_MAP.REMOVED.isNull())
                    .and(INSTANCE_HOST_MAP.HOST_ID.isNotNull()).and(HOST.REMOVED.isNull()))
                .forUpdate().fetch(INSTANCE_HOST_MAP.HOST_ID);
        if (!(request.getAttribute(SCOPE) instanceof Scope scope) || !scope.active().get()
                || !new java.util.HashSet<>(hostIds).equals(new java.util.HashSet<>(scope.hostIds()))) throw fail(409, "PortUpdateConflict");
        if (hostIds.isEmpty()) {
            if ("running".equals(source.getState())) throw fail(422, "PortUnavailable");
            return; // Unallocated desired configuration is checked by the existing allocator on start.
        }
        var host = HOST.as("port_update_host");
        var physicalHosts = create().select(HOST.PHYSICAL_HOST_ID).from(HOST)
                .where(HOST.ID.in(hostIds).and(HOST.PHYSICAL_HOST_ID.isNotNull()));
        // Internal global published inventory: no caller Project filter, names or hidden IDs leave this owner.
        for (Record row : create().select(PORT.ID, PORT.PUBLIC_PORT, PORT.PRIVATE_PORT, PORT.PROTOCOL, PORT.DATA,
                    INSTANCE.DATA, INSTANCE_HOST_MAP.HOST_ID)
                .from(PORT).join(INSTANCE_HOST_MAP).on(INSTANCE_HOST_MAP.INSTANCE_ID.eq(PORT.INSTANCE_ID))
                .join(INSTANCE).on(INSTANCE.ID.eq(PORT.INSTANCE_ID))
                .join(host).on(host.ID.eq(INSTANCE_HOST_MAP.HOST_ID))
                .where(PORT.ID.ne(selected.getId()).and(PORT.REMOVED.isNull()).and(INSTANCE.REMOVED.isNull())
                    .and(INSTANCE_HOST_MAP.REMOVED.isNull()).and(host.REMOVED.isNull())
                    .and(host.ID.in(hostIds).or(host.PHYSICAL_HOST_ID.in(physicalHosts)))
                    .and(INSTANCE.STATE.in("starting", "restarting", "running", "stopping", "migrating")))
                .orderBy(PORT.ID).forUpdate()) {
            if (!Objects.equals(selected.getProtocol(), row.get(PORT.PROTOCOL))) continue;
            String ownerMode = DataAccessor.fromMap(row.get(INSTANCE.DATA)).withScopeKey("fields").withKey("networkMode").as(String.class);
            Integer ownerPort = "host".equalsIgnoreCase(ownerMode) ? row.get(PORT.PRIVATE_PORT) : row.get(PORT.PUBLIC_PORT);
            String ownerBind = "host".equalsIgnoreCase(ownerMode) ? "0.0.0.0"
                    : DataAccessor.fromMap(row.get(PORT.DATA)).withScopeKey("fields").withKey("bindAddress").as(String.class);
            if (requested.equals(ownerPort)) {
                boolean overlap;
                try { overlap = PortBindingAddress.overlaps(DataAccessor.fieldString(selected, "bindAddress"), ownerBind); }
                catch (RuntimeException invalidInventory) { throw fail(422, "PortUnavailable"); }
                if (overlap) throw fail(422, "PortUnavailable");
            }
        }
    }

    private Map<String, Object> expected(ApiRequest request) {
        Object raw = request.getAttribute(EXPECTED);
        Map<String, Object> trusted = raw instanceof Expected expected ? expected.value() : null;
        String header = request.getServletContext() == null ? null : request.getServletContext().getRequest().getHeader(BEFORE_HEADER);
        if (header == null) {
            if (trusted != null) return trusted;
            if (request.getAttribute(REQUIRED) == Required.BEFORE) throw fail(428, "PortUpdatePreconditionRequired");
            return null;
        }
        if (header.length() > 2048) throw fail(422, "PortUpdatePreconditionInvalid");
        Map<String, Object> value;
        try { value = json.readValue(header); }
        catch (IOException | RuntimeException invalid) { throw fail(422, "PortUpdatePreconditionInvalid"); }
        if (value == null || !value.keySet().equals(BEFORE_FIELDS)) throw fail(422, "PortUpdatePreconditionInvalid");
        for (String field : List.of("uuid", "sourceUuid"))
            if (!(value.get(field) instanceof String text) || text.isBlank() || text.length() > 128) throw fail(422, "PortUpdatePreconditionInvalid");
        for (String field : List.of("accountId", "instanceId")) {
            Object id = value.get(field);
            if (id instanceof String text) {
                Object parsed = ApiContext.getContext().getIdFormatter().parseId(text);
                try { id = Long.valueOf(Objects.toString(parsed, "")); }
                catch (NumberFormatException invalid) { throw fail(422, "PortUpdatePreconditionInvalid"); }
            }
            if (!(id instanceof Number number) || number.longValue() <= 0 || number.doubleValue() != number.longValue())
                throw fail(422, "PortUpdatePreconditionInvalid");
            value.put(field, ((Number) id).longValue());
        }
        value.put("publicPort", publicPort(value.get("publicPort")));
        value.put("privatePort", publicPort(value.get("privatePort")));
        if (value.get("privatePort") == null || !Set.of("tcp", "udp").contains(Objects.toString(value.get("protocol"), ""))
                || value.get("bindAddress") != null && (!(value.get("bindAddress") instanceof String bind) || bind.length() > 128))
            throw fail(422, "PortUpdatePreconditionInvalid");
        if (trusted != null && !trusted.equals(value)) throw fail(409, "PortUpdateConflict");
        return value;
    }

    public static Integer publicPort(Object value) {
        if (value == null) return null;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.longValue()
                || number.longValue() < 1 || number.longValue() > 65535) throw fail(422, "PortUpdateInputInvalid");
        return ((Number) value).intValue();
    }

    private static ClientVisibleException fail(int status, String code) { return new ClientVisibleException(status, code, code, null); }
}
