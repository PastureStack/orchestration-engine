package io.cattle.platform.iaas.api.auth.apikey;

import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Credential;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Explicit wire/persistence codec: never deserialize client input into a Credential. */
public final class ApiKeyPolicyCodec {
    public static final String POLICY = "apiKeyPolicy";
    public static final String REVISION = "apiKeyPolicyRevision";
    public static final String OWNER = "apiKeyPolicyOwnerId";
    public static final String FORMAT = "apiKeyPolicyFormatVersion";
    public static final int FORMAT_VERSION = 1;
    public static final Set<String> OPERATIONS = Set.of("read", "create", "update", "upgrade", "delete", "exec", "logs", "export");

    public ApiKeyPolicy decode(Object value) {
        Map<?, ?> input = map(value);
        only(input, Set.of("mode", "defaultEffect", "expiresAt", "rules"));
        ApiKeyPolicy.Mode mode = ApiKeyPolicy.Mode.valueOf(text(input.get("mode")).toUpperCase(Locale.ROOT));
        ApiKeyPolicy.Effect effect = ApiKeyPolicy.Effect.valueOf(text(input.get("defaultEffect")).toUpperCase(Locale.ROOT));
        Object expiry = input.get("expiresAt");
        Instant expiresAt = expiry == null ? null : Instant.parse(text(expiry));
        if (!(input.get("rules") instanceof List<?> rawRules) || rawRules.size() > 128) {
            throw new IllegalArgumentException("Policy rules must be an array of at most 128 rules");
        }
        List<ApiKeyPolicy.Rule> rules = new ArrayList<>();
        for (Object rawRule : rawRules) {
            Map<?, ?> rule = map(rawRule);
            only(rule, Set.of("id", "effect", "scope", "operations"));
            Map<?, ?> rawScope = map(rule.get("scope"));
            only(rawScope, Set.of("kind", "resourceType", "resourceId"));
            ApiKeyPolicy.Scope scope = new ApiKeyPolicy.Scope(
                    ApiKeyPolicy.ScopeKind.valueOf(text(rawScope.get("kind")).toUpperCase(Locale.ROOT)),
                    canonicalResourceType(nullableText(rawScope.get("resourceType"))), nullableText(rawScope.get("resourceId")));
            if (!(rule.get("operations") instanceof List<?> operations) || operations.size() > 64) {
                throw new IllegalArgumentException("Rule operations must be a bounded array");
            }
            Set<String> ids = new TreeSet<>();
            for (Object operation : operations) {
                if (!ids.add(text(operation))) {
                    throw new IllegalArgumentException("Duplicate operation ID");
                }
            }
            if (!OPERATIONS.containsAll(ids)) throw new IllegalArgumentException("Unknown operation ID");
            rules.add(new ApiKeyPolicy.Rule(text(rule.get("id")),
                    ApiKeyPolicy.Effect.valueOf(text(rule.get("effect")).toUpperCase(Locale.ROOT)), scope, ids));
        }
        if ((mode == ApiKeyPolicy.Mode.FULL && (effect != ApiKeyPolicy.Effect.ALLOW || !rules.isEmpty()))
                || (mode == ApiKeyPolicy.Mode.CLOSED && (effect != ApiKeyPolicy.Effect.DENY || !rules.isEmpty()))) {
            throw new IllegalArgumentException("Full and closed modes use their fixed default and no rules");
        }
        rules.sort(Comparator.comparing(ApiKeyPolicy.Rule::id));
        return new ApiKeyPolicy(mode, effect, expiresAt, rules);
    }

    public Map<String, Object> encode(ApiKeyPolicy policy) {
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("mode", lower(policy.getMode()));
        output.put("defaultEffect", lower(policy.getDefaultEffect()));
        output.put("expiresAt", policy.getExpiresAt() == null ? null : policy.getExpiresAt().toString());
        List<Map<String, Object>> rules = new ArrayList<>();
        policy.getRules().stream().sorted(Comparator.comparing(ApiKeyPolicy.Rule::id)).forEach(rule -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rule.id());
            item.put("effect", lower(rule.effect()));
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("kind", lower(rule.scope().kind()));
            if (rule.scope().resourceType() != null) scope.put("resourceType", canonicalResourceType(rule.scope().resourceType()));
            if (rule.scope().resourceId() != null) scope.put("resourceId", rule.scope().resourceId());
            item.put("scope", scope);
            item.put("operations", new ArrayList<>(new TreeSet<>(rule.operations())));
            rules.add(item);
        });
        output.put("rules", rules);
        return output;
    }

    public ApiKeyPolicy read(Credential credential) {
        Map<String, Object> data = credential.getData();
        Object value = data == null ? null : data.get(POLICY);
        if (value == null) {
            if (CredentialConstants.KIND_API_KEY_RESTRICTED.equals(credential.getKind())) {
                throw new IllegalArgumentException("Restricted credential requires a stored policy");
            }
            return null;
        }
        if (number(data.get(FORMAT)) != FORMAT_VERSION || number(data.get(OWNER)) != credential.getAccountId()
                || number(data.get(REVISION)) < 1) {
            throw new IllegalArgumentException("Invalid policy ownership, format or revision");
        }
        return decode(value);
    }

    public long revision(Credential credential) {
        return read(credential) == null ? 0 : number(credential.getData().get(REVISION));
    }

    public Map<String, Object> store(Credential credential, ApiKeyPolicy policy, long revision) {
        Map<String, Object> data = credential.getData() == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(credential.getData());
        data.put(POLICY, encode(policy));
        data.put(REVISION, revision);
        data.put(OWNER, credential.getAccountId());
        data.put(FORMAT, FORMAT_VERSION);
        return data;
    }

    public static long number(Object value) {
        if (!(value instanceof Number number) || number.longValue() < 0
                || number.doubleValue() != number.longValue()) {
            throw new IllegalArgumentException("Expected a nonnegative integer");
        }
        return number.longValue();
    }

    public static String canonicalResourceType(String type) {
        if (type == null) return null;
        return switch (type) {
            case "instance" -> "container";
            case "environment" -> "stack";
            case "apiKeyRestricted" -> "apiKey";
            default -> type;
        };
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static Map<?, ?> map(Object value) {
        if (!(value instanceof Map<?, ?> result)) throw new IllegalArgumentException("Expected policy object");
        return result;
    }

    private static String text(Object value) {
        if (!(value instanceof String result) || result.isBlank() || !result.equals(result.trim())) {
            throw new IllegalArgumentException("Expected canonical text");
        }
        return result;
    }

    private static String nullableText(Object value) {
        return value == null ? null : text(value);
    }

    private static void only(Map<?, ?> input, Set<String> keys) {
        if (!keys.containsAll(input.keySet())) throw new IllegalArgumentException("Unknown policy field");
    }
}
