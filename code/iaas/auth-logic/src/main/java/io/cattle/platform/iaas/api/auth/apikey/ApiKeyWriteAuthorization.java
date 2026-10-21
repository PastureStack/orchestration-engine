package io.cattle.platform.iaas.api.auth.apikey;

import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;

import io.cattle.platform.core.constants.CredentialConstants;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.write.WriteAuthorization;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;

/** A request admitted before revocation cannot commit a later, waiting SQL write. */
public class ApiKeyWriteAuthorization extends AbstractJooqDao implements WriteAuthorization {
    private static final String WRITE = ApiKeyWriteAuthorization.class.getName();
    private static final Set<String> KEY_TYPES = Set.of(CredentialConstants.TYPE,
            CredentialConstants.KIND_API_KEY, CredentialConstants.KIND_API_KEY_RESTRICTED);
    Clock clock = Clock.systemUTC();

    private record Write(ApiKeyCredentialContext key, boolean selfPolicy, boolean reduction, boolean policyCompleted) { }

    @Override public void beforeWrite(ApiRequest request) {
        ApiKeyCredentialContext key = ApiKeyCredentialContext.get(request);
        if (key == null) return;
        boolean self = selfManagement(request, key);
        boolean policy = self && "PUT".equals(request.getMethod());
        boolean reduction = self && ("DELETE".equals(request.getMethod())
                || "POST".equals(request.getMethod()) && request.getAction() != null
                        && Set.of("deactivate", "remove", "purge").contains(request.getAction()));
        // Policy PUT already has a logical lock followed by a SQL CAS. Do not
        // acquire its row lock first and invert that established lock order.
        request.setAttribute(WRITE, new Write(key, policy, reduction, false));
    }

    @Override public void beforeCommit(ApiRequest request) {
        if (!(request.getAttribute(WRITE) instanceof Write write)) return;
        Credential current = lock(write.key().credentialId());
        if (write.reduction() || write.selfPolicy() && write.policyCompleted()) {
            // Revoke/delete can only reduce this exact Key's authority. A policy
            // change needs the owning manager's successful, admitted-revision CAS.
            // Neither exception can permit a credential-owner rebind.
            if (current != null && !Objects.equals(current.getAccountId(), write.key().principalAccountId())) {
                deny(request, "OwnerPermissionDenied");
            }
        } else {
            validate(request, write.key(), current);
        }
    }

    private Credential lock(long id) {
        // InnoDB locking reads see the latest committed row even if an earlier
        // manager SELECT established a repeatable-read snapshot. Keep this lock
        // through the surrounding write transaction's commit, not just this call.
        return create().selectFrom(CREDENTIAL).where(CREDENTIAL.ID.eq(id)).forUpdate().fetchOne();
    }

    static void beforeSelfPolicyUpdate(ApiRequest request, Credential current) {
        if (request.getAttribute(WRITE) instanceof Write write && write.selfPolicy()
                && Objects.equals(current.getId(), write.key().credentialId())) {
            validate(request, write.key(), current, Clock.systemUTC());
        }
    }

    static void selfPolicyUpdated(ApiRequest request, Credential current) {
        if (request.getAttribute(WRITE) instanceof Write write && write.selfPolicy()
                && Objects.equals(current.getId(), write.key().credentialId())) {
            request.setAttribute(WRITE, new Write(write.key(), true, false, true));
        }
    }

    private void validate(ApiRequest request, ApiKeyCredentialContext key, Credential current) {
        validate(request, key, current, clock);
    }

    private static void validate(ApiRequest request, ApiKeyCredentialContext key, Credential current, Clock clock) {
        if (current == null || current.getRemoved() != null || !"active".equals(current.getState())
                || !(CredentialConstants.KIND_API_KEY.equals(current.getKind())
                        || CredentialConstants.KIND_API_KEY_RESTRICTED.equals(current.getKind()))) deny(request, "ApiKeyRevoked");
        if (!Objects.equals(current.getAccountId(), key.principalAccountId())) deny(request, "OwnerPermissionDenied");
        ApiKeyPolicy policy;
        try {
            ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
            policy = codec.read(current);
            if (codec.revision(current) != key.revision() || !Objects.equals(current.getKind(), key.kind())) {
                deny(request, "ApiKeyPolicyChanged");
            }
        } catch (IllegalArgumentException invalid) {
            deny(request, "ApiKeyPolicyInvalid");
            return;
        }
        if (policy != null && policy.getExpiresAt() != null && !clock.instant().isBefore(policy.getExpiresAt())) {
            deny(request, "ApiKeyExpired");
        }
        // Owner RBAC and custom resource scope remain with their existing admission
        // and process boundaries. Full/legacy gain no registry or ancestry limit.
    }

    private boolean selfManagement(ApiRequest request, ApiKeyCredentialContext key) {
        if (key.restricted() || request.getId() == null || request.getSchemaFactory() == null) return false;
        String type = request.getSchemaFactory().getSchemaName(request.getType());
        if (type == null || !KEY_TYPES.contains(type)) return false;
        Class<?> recordType = request.getSchemaFactory().getSchemaClass(type, true);
        if (recordType == null || !Credential.class.isAssignableFrom(recordType)) return false;
        // ValidationHandler has already converted the URL ID to its numeric form.
        return Long.toString(key.credentialId()).equals(request.getId());
    }

    private static void deny(ApiRequest request, String code) {
        // Admission already recorded its decision. Only the completion event must
        // reflect this late denial; do not emit another decision/audit event here.
        request.setAttribute("apiKey.audit.decision", "DENY");
        request.setAttribute("apiKey.audit.reason", code);
        throw new ClientVisibleException(403, code, "This API key is no longer authorized to complete this operation.", null);
    }
}
