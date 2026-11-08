package io.cattle.platform.iaas.api.filter.registry;

import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;
import static io.cattle.platform.core.model.tables.StoragePoolTable.STORAGE_POOL;

import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.StoragePool;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.engine.process.ProcessStateTransitionGuard;
import io.cattle.platform.lock.Lock;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.lock.definition.Namespace;
import io.cattle.platform.process.common.lock.ResourceChangeLock;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.cattle.platform.iaas.api.filter.common.AbstractDefaultResourceManagerFilter;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Native Registry references and removing transitions share the existing resource process locks. */
public class RegistryCredentialReferenceGuard extends AbstractDefaultResourceManagerFilter implements ProcessStateTransitionGuard {
    private static final String EXPECTED = "native.registry.remove.expected";
    private static final Set<String> REMOVING = Set.of("removing", "removed", "purging", "purged");
    private record Expected(long id, String uuid, long account, Consumer<StoragePool> check) { }
    private final Set<String> quarantined = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @Inject LockManager locks;
    @Inject @Named("CoreTransactionManager") PlatformTransactionManager transactions;
    @Inject RegistryRead rows;

    /** Private Java value only; no request body or header can supply this conditional precondition. */
    public static <T> T withRemovalPrecondition(ApiRequest request, long id, String uuid, long account,
            Consumer<StoragePool> check, Supplier<T> next) {
        if (id <= 0 || uuid == null || uuid.isBlank() || account <= 0 || check == null) throw conflict();
        Object previous = request.getAttribute(EXPECTED);
        request.setAttribute(EXPECTED, new Expected(id, uuid, account, check));
        try { return next.get(); }
        finally { request.setAttribute(EXPECTED, previous); }
    }

    @Override public String[] getTypes() { return new String[] { "registry", "registryCredential" }; }
    @Override public Class<?>[] getTypeClasses() { return new Class<?>[] { StoragePool.class, Credential.class }; }

    @Override public Object create(String type, ApiRequest request, ResourceManager next) {
        var body = CollectionUtils.toMap(request.getRequestObject());
        if (!("registryCredential".equalsIgnoreCase(type) || "credential".equals(type) && "registryCredential".equals(body.get("kind"))))
            return next.create(type, request);
        Long parent = request.proxyRequestObject(Credential.class).getRegistryId();
        return reference(parent, null, () -> next.create(type, request));
    }

    @Override public Object update(String type, String id, ApiRequest request, ResourceManager next) {
        if (!"registryCredential".equalsIgnoreCase(type) && !"credential".equals(type)) return next.update(type, id, request);
        Object visible = next.getById(type, id, ListOptions.forMutationTarget(request, type, id));
        if (!(visible instanceof Credential credential) || !"registryCredential".equals(credential.getKind()))
            return next.update(type, id, request);
        var body = CollectionUtils.toMap(request.getRequestObject());
        Long destination = body.containsKey("registryId") ? request.proxyRequestObject(Credential.class).getRegistryId() : credential.getRegistryId();
        return reference(destination, credential, () -> next.update(type, id, request));
    }

    private <T> T reference(Long destination, Credential seed, Supplier<T> save) {
        if (destination == null || destination <= 0 || seed != null && seed.getRegistryId() == null) throw invalidReference();
        TreeSet<Long> parents = new TreeSet<>(); parents.add(destination);
        if (seed != null) parents.add(seed.getRegistryId());
        return transaction(() -> parentLocks(new ArrayList<>(parents), 0, true, () -> {
            StoragePool parent = rows.parent(destination);
            if (!available(parent)) throw invalidReference();
            if (seed == null) return save.get();
            // Parent locks/rows precede the Credential row and its existing scheduler locks.
            StoragePool previous = rows.parent(seed.getRegistryId());
            if (!available(previous) || !Objects.equals(previous.getAccountId(), parent.getAccountId())) throw invalidReference();
            return lease(new Namespace("schedule").getLockDefinition(new ResourceChangeLock("credential", seed.getId())), () ->
                lease(new ResourceChangeLock("credential", seed.getId()), () -> {
                    Credential current = rows.credential(seed.getId());
                    if (current == null || !"registryCredential".equals(current.getKind())
                            || !Objects.equals(current.getUuid(), seed.getUuid()) || !Objects.equals(current.getRegistryId(), seed.getRegistryId())
                            || !Objects.equals(current.getAccountId(), seed.getAccountId())
                            || !Objects.equals(parent.getAccountId(), current.getAccountId())) throw conflict();
                    return save.get();
                }));
        }));
    }

    @Override public Object delete(String type, String id, ApiRequest request, ResourceManager next) {
        if (!"registry".equals(type) && !"storagePool".equalsIgnoreCase(type)) return next.delete(type, id, request);
        Object visible = next.getById(type, id, ListOptions.forMutationTarget(request, type, id));
        if (!(visible instanceof StoragePool seed) || !"registry".equals(seed.getKind())) return next.delete(type, id, request);
        return withRegistryRemoval(seed.getId(), () -> {
            StoragePool parent = rows.parent(seed.getId());
            if (parent == null || !Objects.equals(seed.getUuid(), parent.getUuid())
                    || !Objects.equals(seed.getAccountId(), parent.getAccountId()) || !"registry".equals(parent.getKind())) throw conflict();
            Object value = request.getAttribute(EXPECTED);
            if (value instanceof Expected expected) {
                if (expected.id() != parent.getId() || expected.account() != parent.getAccountId()
                        || !Objects.equals(expected.uuid(), parent.getUuid()) || !"inactive".equals(parent.getState())
                        || parent.getRemoved() != null) throw conflict();
                expected.check().accept(parent);
            } else if (value != null) throw conflict();
            return next.delete(type, id, request); // Original schema, role/Key authorization and REMOVE scheduler remain the writer.
        });
    }

    /** Typed preview/confirm/apply enter before their first parent/child locking read, in the native owner's lock order. */
    public <T> T withRegistryRemoval(long id, Supplier<T> next) {
        if (id <= 0) throw conflict();
        return transaction(() -> parentLocks(List.of(id), 0, true, next));
    }

    /** Scheduling holds schedule/resource; a worker already holds resource. Neither takes a new child lock here. */
    @Override public boolean transition(Object resource, String field, String state, Supplier<Boolean> next) {
        if (!"state".equals(field) || !REMOVING.contains(state) || !(resource instanceof StoragePool pool)
                || !"registry".equals(pool.getKind())) return next.get();
        return transaction(() -> parentLocks(List.of(pool.getId()), 0, false, () -> {
            StoragePool current = rows.parent(pool.getId());
            if (current == null || !Objects.equals(current.getUuid(), pool.getUuid())
                    || !Objects.equals(current.getAccountId(), pool.getAccountId()) || !"registry".equals(current.getKind())) throw conflict();
            return next.get();
        }));
    }

    private static boolean available(StoragePool pool) {
        return pool != null && "registry".equals(pool.getKind()) && pool.getRemoved() == null
                && pool.getState() != null && !REMOVING.contains(pool.getState());
    }

    private <T> T parentLocks(List<Long> ids, int offset, boolean schedule, Supplier<T> next) {
        if (offset == ids.size()) return next.get();
        ResourceChangeLock lock = new ResourceChangeLock("storagePool", ids.get(offset));
        Supplier<T> resource = () -> lease(lock, () -> parentLocks(ids, offset + 1, schedule, next));
        return schedule ? lease(new Namespace("schedule").getLockDefinition(lock), resource) : resource.get();
    }

    private <T> T transaction(Supplier<T> next) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            if (!TransactionSynchronizationManager.isSynchronizationActive()) throw new ClientVisibleException(503, "RegistryLockUnavailable");
            return next.get();
        }
        return new TransactionTemplate(transactions).execute(status -> next.get());
    }

    private <T> T lease(LockDefinition definition, Supplier<T> next) {
        if (quarantined.contains(definition.getLockId())) throw new ClientVisibleException(503, "RegistryLockUnavailable");
        Lock lock = locks.getLockProvider().getLock(definition);
        try { lock.lock(); }
        catch (RuntimeException | Error failure) {
            try { locks.getLockProvider().releaseLock(lock); }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
        try {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    try { lock.unlockChecked(); }
                    catch (RuntimeException | Error failure) { quarantined.add(definition.getLockId()); throw failure; }
                    locks.getLockProvider().releaseLock(lock);
                }
            });
        } catch (RuntimeException | Error failure) {
            try { lock.unlockChecked(); locks.getLockProvider().releaseLock(lock); }
            catch (RuntimeException | Error cleanup) { quarantined.add(definition.getLockId()); failure.addSuppressed(cleanup); }
            throw failure;
        }
        return next.get(); // Exact provider lease remains held until commit/rollback, including the scheduler's state write.
    }

    private static ClientVisibleException conflict() { return new ClientVisibleException(409, "ResourceChangeConflict"); }
    private static ClientVisibleException invalidReference() {
        return new io.github.ibuildthecloud.gdapi.exception.ValidationErrorException("InvalidReference", "registryId");
    }

    /** Current locking reads avoid an earlier API transaction's repeatable-read snapshot. */
    public static class RegistryRead extends AbstractJooqDao {
        StoragePool parent(long id) { return create().selectFrom(STORAGE_POOL).where(STORAGE_POOL.ID.eq(id)).forUpdate().fetchOne(); }
        Credential credential(long id) {
            return create().select(CREDENTIAL.ID, CREDENTIAL.UUID, CREDENTIAL.ACCOUNT_ID, CREDENTIAL.REGISTRY_ID, CREDENTIAL.KIND)
                    .from(CREDENTIAL).where(CREDENTIAL.ID.eq(id)).forUpdate().fetchOneInto(CREDENTIAL);
        }
    }
}
