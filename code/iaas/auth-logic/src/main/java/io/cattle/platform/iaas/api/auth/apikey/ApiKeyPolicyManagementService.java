package io.cattle.platform.iaas.api.auth.apikey;

import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.utils.ApiUtils;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.iaas.api.auth.dao.AuthDao;
import io.cattle.platform.iaas.api.auth.mfa.MfaCredentialLock;
import io.cattle.platform.iaas.api.auth.mfa.MfaService;
import io.cattle.platform.iaas.api.filter.apikey.ApiKeyPolicyManager;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;
import org.apache.commons.codec.binary.Hex;
import org.jooq.Field;
import org.jooq.impl.DSL;

/** Authoritative policy mutation and preview share validation and canonicalization. */
public class ApiKeyPolicyManagementService extends AbstractJooqDao implements ApiKeyPolicyManager {
    private final ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
    @Inject AuthDao authDao;
    @Inject ObjectManager objectManager;
    @Inject LockManager lockManager;
    @Inject MfaService mfaService;
    @Inject JsonMapper jsonMapper;

    @Override
    public void prepareCreate(Credential credential, ApiRequest request) {
        Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        if (!input.containsKey(ApiKeyPolicyCodec.POLICY)) {
            // Native V1/V2 environment-Key creation is not a policy migration.
            // Its already authorized project/account boundary remains unchanged.
            Set<String> legacyFields = Set.of("type", "name", "description", "accountId", "kind", "publicValue", "secretValue");
            if (!legacyFields.containsAll(input.keySet())
                    || (input.containsKey("type") && !Set.of(CredentialConstants.TYPE, CredentialConstants.KIND_API_KEY).contains(string(input.get("type"))))
                    || CredentialConstants.KIND_API_KEY_RESTRICTED.equals(credential.getKind())
                    || CredentialConstants.KIND_API_KEY_RESTRICTED.equals(request.getType())
                    || (input.containsKey("kind") && !CredentialConstants.KIND_API_KEY.equals(string(input.get("kind"))))) {
                throw error(ResponseCodes.BAD_REQUEST, "ApiKeyPolicyInvalid");
            }
            return;
        }
        validateInput(input, true);
        Account actor = actor();
        ApiKeyPolicy candidate = candidate(input.get(ApiKeyPolicyCodec.POLICY));
        credential.setAccountId(actor.getId());
        credential.setKind(kind(candidate, null));
        credential.setData(codec.store(credential, candidate, 1));
        input.put("accountId", credential.getAccountId());
        input.put("kind", credential.getKind());
        input.put("data", credential.getData());
        // Strip synthetic write inputs. Persist only the server-produced data object.
        input.remove(ApiKeyPolicyCodec.POLICY);
        input.remove(ApiKeyPolicyCodec.REVISION);
        input.remove("securityConfirmation");
    }

    @Override
    public Object update(String type, String id, ApiRequest request, ResourceManager next) {
        Object visible = next.getById(type, id, new ListOptions(request));
        if (!(visible instanceof Credential credential)) return next.update(type, id, request);
        if (!isApiKey(credential)) return next.update(type, id, request);
        final Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        validateInput(input, false);
        return lockManager.lock(new MfaCredentialLock("apiKeyPolicy", String.valueOf(credential.getId())),
                new LockCallback<Object>() {
                    @Override
                    public Object doWithLock() {
                        Credential current = objectManager.reload(credential);
                        requireActive(current);
                        Account actor = actor();
                        ApiKeyPolicy previous = codec.read(current);
                        long revision = codec.revision(current);
                        boolean policyWrite = input.containsKey(ApiKeyPolicyCodec.POLICY);
                        ApiKeyPolicy candidate = policyWrite ? candidate(input.get(ApiKeyPolicyCodec.POLICY)) : previous;
                        if (policyWrite) {
                            requireRevision(input, revision);
                            if (requiresConfirmation(previous, candidate)) {
                                mfaService.consumeSecurityConfirmation(actor, string(input.get("securityConfirmation")),
                                        MfaService.PURPOSE_API_KEY_POLICY_UPDATE,
                                        digest(actor.getId(), current.getId(), revision, candidate));
                            }
                        }
                        Map<String, Object> data = policyWrite ? codec.store(current, candidate, revision + 1)
                                : current.getData();
                        String targetKind = policyWrite ? kind(candidate, current.getKind()) : current.getKind();
                        persist(current, data, targetKind, input);
                        return objectManager.reload(current);
                    }
                });
    }

    public Map<String, Object> preview(ApiRequest request, ResourceManager keyManager) {
        Map<String, Object> input = CollectionUtils.toMap(request.getRequestObject());
        if (!Set.of("apiKeyId", ApiKeyPolicyCodec.POLICY, ApiKeyPolicyCodec.REVISION).containsAll(input.keySet())) {
            throw error(ResponseCodes.BAD_REQUEST, "ApiKeyPolicyInvalid");
        }
        Account actor = actor();
        String keyId = string(input.get("apiKeyId"));
        ApiKeyPolicy previous = null;
        long revision = 0;
        Long internalId = null;
        if (keyId != null) {
            Credential loaded = objectManager.loadResource(Credential.class, keyId);
            if (loaded == null || !isApiKey(loaded)) throw error(ResponseCodes.NOT_FOUND, "NotFound");
            Object visible = keyManager.getById(loaded.getKind(), keyId, new ListOptions(request));
            if (!(visible instanceof Credential credential)) throw error(ResponseCodes.NOT_FOUND, "NotFound");
            requireActive(credential);
            previous = codec.read(credential);
            revision = codec.revision(credential);
            requireRevision(input, revision);
            internalId = credential.getId();
            requireWritableSchema(request, credential.getKind(), false);
        } else {
            requireWritableSchema(request, CredentialConstants.KIND_API_KEY, true);
        }
        ApiKeyPolicy candidate = candidate(input.get(ApiKeyPolicyCodec.POLICY));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ApiKeyPolicyCodec.POLICY, codec.encode(candidate));
        result.put(ApiKeyPolicyCodec.REVISION, revision);
        result.put("purpose", MfaService.PURPOSE_API_KEY_POLICY_UPDATE);
        result.put("requestDigest", digest(actor.getId(), internalId, revision, candidate));
        result.put("confirmationRequired", internalId != null && requiresConfirmation(previous, candidate));
        return result;
    }

    @Override
    public Map<String, Object> output(Credential credential) {
        ApiKeyPolicy policy = codec.read(credential);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ApiKeyPolicyCodec.POLICY, codec.encode(policy == null ? full() : policy));
        result.put(ApiKeyPolicyCodec.REVISION, codec.revision(credential));
        return result;
    }

    protected void persist(Credential current, Map<String, Object> data, String targetKind,
                           Map<String, Object> input) {
        // Compare the exact stored policy revision/data and current live state in one SQL write.
        // Revocation never writes this service's lock, so state must be part of the CAS predicate.
        Field<String> rawData = DSL.field(CREDENTIAL.DATA.getQualifiedName(), String.class);
        String original = create().select(rawData).from(CREDENTIAL).where(CREDENTIAL.ID.eq(current.getId())).fetchOne(rawData);
        try {
            Map<String, Object> stored = original == null ? null : jsonMapper.readValue(original);
            if (!java.util.Objects.equals(stored, current.getData())) {
                throw error(ResponseCodes.CONFLICT, "ApiKeyPolicyRevisionConflict");
            }
        } catch (IOException e) {
            throw error(ResponseCodes.CONFLICT, "ApiKeyPolicyRevisionConflict");
        }
        int changed = create().update(CREDENTIAL)
                .set(CREDENTIAL.DATA, data)
                .set(CREDENTIAL.KIND, targetKind)
                .set(CREDENTIAL.NAME, input.containsKey("name") ? string(input.get("name")) : current.getName())
                .set(CREDENTIAL.DESCRIPTION, input.containsKey("description") ? string(input.get("description")) : current.getDescription())
                .where(CREDENTIAL.ID.eq(current.getId()).and(CREDENTIAL.STATE.eq(CommonStatesConstants.ACTIVE))
                        .and(CREDENTIAL.REMOVED.isNull()).and(CREDENTIAL.KIND.eq(current.getKind()))
                        .and(CREDENTIAL.ACCOUNT_ID.eq(current.getAccountId()))
                        .and(original == null ? rawData.isNull() : rawData.eq(original)))
                .execute();
        if (changed != 1) throw error(ResponseCodes.CONFLICT, "ApiKeyPolicyRevisionConflict");
    }

    /** Conservative implication check: uncertain custom changes require confirmation. */
    public boolean requiresConfirmation(ApiKeyPolicy previous, ApiKeyPolicy candidate) {
        if (previous == null) previous = full();
        Instant before = previous.getExpiresAt();
        Instant after = candidate.getExpiresAt();
        if (before != null && (after == null || after.isAfter(before))) return true;
        if (candidate.getMode() == ApiKeyPolicy.Mode.CLOSED) return false;
        if (previous.getMode() == ApiKeyPolicy.Mode.FULL) return false;
        if (codec.encode(previous).equals(codec.encode(candidate))) return false;
        if (candidate.getMode() == ApiKeyPolicy.Mode.FULL) return true;
        // Keep the existing allow/default policy and only add denies or remove allows.
        if (previous.getMode() != ApiKeyPolicy.Mode.CUSTOM
                || previous.getDefaultEffect() != candidate.getDefaultEffect()) return true;
        return !previous.getRules().stream().filter(rule -> rule.effect() == ApiKeyPolicy.Effect.DENY)
                .allMatch(candidate.getRules()::contains)
                || !candidate.getRules().stream().filter(rule -> rule.effect() == ApiKeyPolicy.Effect.ALLOW)
                .allMatch(previous.getRules()::contains);
    }

    public String digest(long actorId, Long keyId, long revision, ApiKeyPolicy candidate) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("purpose", MfaService.PURPOSE_API_KEY_POLICY_UPDATE);
        document.put("actorId", actorId);
        document.put("keyId", keyId);
        document.put("revision", revision);
        document.put("policy", codec.encode(candidate));
        try {
            return Hex.encodeHexString(MessageDigest.getInstance("SHA-256").digest(jsonMapper.writeValueAsBytes(document)));
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot encode API key policy confirmation", e);
        }
    }

    protected Account actor() {
        Policy policy = ApiUtils.getPolicy();
        Account actor = policy == null ? null : authDao.getAccountById(policy.getAuthenticatedAsAccountId());
        if (actor == null || !CommonStatesConstants.ACTIVE.equals(actor.getState()) || actor.getRemoved() != null) {
            throw error(ResponseCodes.FORBIDDEN, "PermissionDenied");
        }
        return actor;
    }

    private ApiKeyPolicy candidate(Object input) {
        try {
            ApiKeyPolicy policy = input == null ? full() : codec.decode(input);
            if (policy.getExpiresAt() != null && !Instant.now().isBefore(policy.getExpiresAt())) {
                throw error(ResponseCodes.BAD_REQUEST, "ApiKeyExpiryInvalid");
            }
            return policy;
        } catch (IllegalArgumentException e) {
            throw error(ResponseCodes.BAD_REQUEST, "ApiKeyPolicyInvalid");
        }
    }

    private void requireWritableSchema(ApiRequest request, String kind, boolean create) {
        Schema schema = request.getSchemaFactory().getSchema(kind);
        if (schema == null || !(create ? schema.getCollectionMethods().contains("POST")
                : schema.getResourceMethods().contains("PUT"))) {
            throw error(ResponseCodes.FORBIDDEN, "PermissionDenied");
        }
    }

    private void validateInput(Map<String, Object> input, boolean create) {
        Set<String> allowed = create ? Set.of("name", "description", "accountId", "kind", "publicValue", "secretValue",
                ApiKeyPolicyCodec.POLICY, ApiKeyPolicyCodec.REVISION, "securityConfirmation")
                : Set.of("name", "description", ApiKeyPolicyCodec.POLICY, ApiKeyPolicyCodec.REVISION, "securityConfirmation");
        if (!allowed.containsAll(input.keySet())) throw error(ResponseCodes.BAD_REQUEST, "ApiKeyPolicyInvalid");
    }

    private void requireRevision(Map<String, Object> input, long revision) {
        try {
            if (ApiKeyPolicyCodec.number(input.get(ApiKeyPolicyCodec.REVISION)) != revision) {
                throw error(ResponseCodes.CONFLICT, "ApiKeyPolicyRevisionConflict");
            }
        } catch (IllegalArgumentException e) {
            throw error(ResponseCodes.CONFLICT, "ApiKeyPolicyRevisionConflict");
        }
    }

    private static boolean isApiKey(Credential credential) {
        return CredentialConstants.KIND_API_KEY.equals(credential.getKind())
                || CredentialConstants.KIND_API_KEY_RESTRICTED.equals(credential.getKind());
    }

    private static String kind(ApiKeyPolicy policy, String existingKind) {
        // Once migrated, keep the rejecting kind even after a later widening.
        return CredentialConstants.KIND_API_KEY_RESTRICTED.equals(existingKind)
                || policy.getMode() != ApiKeyPolicy.Mode.FULL || policy.getExpiresAt() != null
                ? CredentialConstants.KIND_API_KEY_RESTRICTED : CredentialConstants.KIND_API_KEY;
    }

    private static ApiKeyPolicy full() {
        return new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, Collections.emptyList());
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static void requireActive(Credential credential) {
        if (credential == null || !CommonStatesConstants.ACTIVE.equals(credential.getState()) || credential.getRemoved() != null) {
            throw error(ResponseCodes.CONFLICT, "ApiKeyRevoked");
        }
    }

    private static ClientVisibleException error(int status, String code) {
        return new ClientVisibleException(status, code);
    }
}
