package io.cattle.platform.iaas.api.auth.change;

import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;

import io.cattle.platform.core.model.StoragePool;
import io.cattle.platform.object.util.ObjectUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jooq.DSLContext;

/** Registry removal metadata only. No credential value, mutation, or extra native action. */
public final class AiRegistryRemovalChange {
    static final String CREDENTIALS = "registryRemovalCredentials";
    public static final int MAX_CREDENTIALS = 256;
    private AiRegistryRemovalChange() { }

    public static boolean supports(AiResourceChangeDescriptor descriptor) {
        return "registry".equals(descriptor.resourceType()) && "remove".equals(descriptor.operation());
    }

    static boolean binding(Map<String, Object> binding) {
        return binding != null && "registry".equals(binding.get("resourceType")) && "remove".equals(binding.get("operation"))
                && "DELETE".equals(binding.get("method")) && binding.get("action") == null && "delete".equals(binding.get("keyOperation"));
    }

    static List<Map<String, Object>> credentials(DSLContext sql, long registryId, long accountId,
            boolean locking, List<Map<String, Object>> captured) {
        List<Long> ids = captured.stream().map(row -> Long.parseLong((String) row.get("id"))).toList();
        var condition = CREDENTIAL.REGISTRY_ID.eq(registryId);
        if (!ids.isEmpty()) condition = condition.or(CREDENTIAL.ID.in(ids));
        var query = sql.select(CREDENTIAL.ID, CREDENTIAL.UUID, CREDENTIAL.ACCOUNT_ID, CREDENTIAL.REGISTRY_ID,
                CREDENTIAL.KIND, CREDENTIAL.STATE, CREDENTIAL.REMOVED).from(CREDENTIAL)
                .where(condition).orderBy(CREDENTIAL.ID).limit(MAX_CREDENTIALS + 1);
        var records = locking ? query.forUpdate().fetch() : query.fetch();
        if (records.size() > MAX_CREDENTIALS) throw unsupported();
        List<Map<String, Object>> result = new ArrayList<>(); Set<String> uuids = new HashSet<>();
        for (var record : records) {
            Long id = record.get(CREDENTIAL.ID), account = record.get(CREDENTIAL.ACCOUNT_ID), parent = record.get(CREDENTIAL.REGISTRY_ID);
            String uuid = record.get(CREDENTIAL.UUID), kind = record.get(CREDENTIAL.KIND), state = record.get(CREDENTIAL.STATE);
            if (id == null || id <= 0 || !Objects.equals(account, accountId) || !Objects.equals(parent, registryId)
                    || uuid == null || uuid.isBlank() || !uuids.add(uuid) || !"registryCredential".equals(kind)
                    || state == null || state.isBlank()) throw unsupported();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id.toString()); row.put("uuid", uuid); row.put("accountId", account.toString());
            row.put("registryId", parent.toString()); row.put("kind", kind); row.put("state", state);
            var removed = record.get(CREDENTIAL.REMOVED);
            row.put("removed", removed == null ? null : Long.toString(removed.getTime())); result.add(row);
        }
        return result;
    }

    static List<Map<String, Object>> captured(Object value) {
        if (!(value instanceof List<?> rows) || rows.size() > MAX_CREDENTIALS) throw unsupported();
        List<Map<String, Object>> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row) || !row.keySet().equals(Set.of("id", "uuid", "accountId", "registryId", "kind", "state", "removed"))
                    || !(row.get("id") instanceof String id) || !id.matches("[1-9][0-9]*") || !ids.add(id)
                    || !(row.get("uuid") instanceof String uuid) || uuid.isBlank()
                    || !(row.get("accountId") instanceof String account) || !account.matches("[1-9][0-9]*")
                    || !(row.get("registryId") instanceof String registry) || !registry.matches("[1-9][0-9]*")
                    || !"registryCredential".equals(row.get("kind")) || !(row.get("state") instanceof String state) || state.isBlank()
                    || row.get("removed") != null && (!(row.get("removed") instanceof String time) || !time.matches("[0-9]+"))) throw unsupported();
            try { Long.parseLong(id); Long.parseLong(account); Long.parseLong(registry); }
            catch (NumberFormatException invalid) { throw unsupported(); }
            Map<String, Object> copy = new LinkedHashMap<>(); row.forEach((key, entry) -> copy.put((String) key, entry)); result.add(copy);
        }
        return result;
    }

    static int affected(List<Map<String, Object>> rows) {
        return (int) rows.stream().filter(row -> row.get("removed") == null).count();
    }

    static Map<String, Object> configuration(Map<String, Object> snapshot) {
        Map<String, Object> result = new LinkedHashMap<>(snapshot);
        result.remove("state"); result.remove("removed"); result.remove(CREDENTIALS); return result;
    }

    static boolean receiptIdentity(Object target, Map<String, Object> before) {
        return "registry".equals(before.get("kind")) && target instanceof StoragePool pool && "registry".equals(pool.getKind()) && pool.getId() != null
                && Objects.equals(before.get("uuid"), pool.getUuid()) && pool.getAccountId() != null
                && before.get("id") instanceof Number id && id.longValue() == pool.getId()
                && before.get("accountId") instanceof Number account && account.longValue() == pool.getAccountId();
    }

    static boolean complete(Object target, Map<String, Object> before, Map<String, Object> current,
            List<Map<String, Object>> rows) {
        if (!receiptIdentity(target, before) || ObjectUtils.getRemoved(target) == null
                || !Set.of("removed", "purging", "purged").contains(Objects.toString(ObjectUtils.getState(target), ""))
                || !configuration(before).equals(configuration(current))) return false;
        Map<String, Map<String, Object>> expected = new LinkedHashMap<>();
        for (Map<String, Object> row : captured(before.get(CREDENTIALS))) expected.put((String) row.get("id"), row);
        for (Map<String, Object> row : rows) {
            Map<String, Object> original = expected.remove(row.get("id"));
            if (original == null || !identity(original).equals(identity(row)) || row.get("removed") == null
                    || !Set.of("removed", "purging", "purged").contains(row.get("state"))) return false;
        }
        // Missing captured IDs were explicitly included in the raw SQL predicate: genuine physical absence, never an API 403/404.
        return true;
    }

    private static Map<String, Object> identity(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row); result.remove("state"); result.remove("removed"); return result;
    }

    private static io.github.ibuildthecloud.gdapi.exception.ClientVisibleException unsupported() {
        return AiResourceChangeDescriptor.error("ResourceChangeUnsupported");
    }
}
