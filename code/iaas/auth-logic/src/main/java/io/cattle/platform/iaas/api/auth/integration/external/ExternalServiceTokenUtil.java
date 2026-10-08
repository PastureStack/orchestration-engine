package io.cattle.platform.iaas.api.auth.integration.external;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigListProperty;
import io.cattle.platform.core.constants.IdentityConstants;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.iaas.api.auth.AbstractTokenUtil;
import io.cattle.platform.iaas.api.auth.SecurityConstants;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;

public class ExternalServiceTokenUtil extends AbstractTokenUtil {

    private static final ConfigListProperty<String> SUPPORTED_EXTERNAL_ID_TYPES =
            ArchaiusUtil.getStringListProperty("auth.service.external.id.types");
    private static final Set<String> REQUIRED_OIDC_IDENTITY_TYPES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList("oidc_user", "oidc_group")));

    @Override
    protected String accessMode() {
        return ServiceAuthConstants.ACCESS_MODE.get();
    }

    @Override
    protected String accessToken() {
        return ServiceAuthConstants.ACCESS_TOKEN;
    }

    @Override
    protected void postAuthModification(Account account) {
        ApiRequest request = ApiContext.getContext().getApiRequest();
        String accessToken = (String) request.getAttribute(ServiceAuthConstants.ACCESS_TOKEN);
        DataAccessor.fields(account).withKey(ServiceAuthConstants.ACCESS_TOKEN)
                .set(accessToken);
        getObjectManager().persist(account);
    }

    @Override
    public String userType() {
        return ServiceAuthConstants.USER_TYPE.get();
    }


    public String identitySeparator() {
        return ServiceAuthConstants.IDENTITY_SEPARATOR.get();
    }

    /**
     * Converts one identity returned by the external authentication service and
     * validates its type before account lookup, access-policy evaluation, or
     * any persistent account mutation can occur.
     */
    public Identity jsonToValidatedExternalIdentity(Map<String, Object> jsonData) {
        Identity identity = jsonToIdentity(jsonData);
        if (!isSupportedExternalIdentityType(identity)) {
            throw new ClientVisibleException(ResponseCodes.BAD_REQUEST, IdentityConstants.INVALID_TYPE,
                    "Identity externalIdType is invalid", null);
        }
        return identity;
    }

    public boolean isSupportedExternalIdentityType(Identity identity) {
        if (identity == null || StringUtils.isBlank(identity.getExternalIdType())) {
            return false;
        }
        String externalIdType = identity.getExternalIdType();
        if (ProjectConstants.RANCHER_ID.equalsIgnoreCase(externalIdType)) {
            return false;
        }
        if (REQUIRED_OIDC_IDENTITY_TYPES.contains(externalIdType)) {
            return true;
        }
        List<String> configured = SUPPORTED_EXTERNAL_ID_TYPES.get();
        return configured != null && configured.contains(externalIdType);
    }

    /**
     * Decode an already verified owner session without reading a request, a
     * Cookie, or getJWT(). The caller verifies the durable token record and
     * signature/expiry first. A legacy token must bind its user to this account;
     * modern tokens carry the stable principal issued by the platform.
     */
    public Set<Identity> identitiesForAccount(Account account, Map<String, Object> payload) {
        if (account == null || account.getId() == null || payload == null
                || !(payload.get(ID_LIST) instanceof List<?> ids)
                || ids.stream().anyMatch(id -> !(id instanceof String))) {
            return null;
        }
        Object principal = payload.get(PRINCIPAL_ACCOUNT_ID);
        if (principal != null) {
            if (!String.valueOf(account.getId()).equals(String.valueOf(principal))) return null;
        } else {
            Object value = payload.get(USER_IDENTITY);
            Identity user = value instanceof Identity ? (Identity) value
                    : value instanceof Map<?, ?> ? jsonToIdentity(CollectionUtils.toMap(value)) : null;
            if (!ownsExternalUser(account, user)
                    || !java.util.Objects.equals(payload.get(ACCOUNT_ID), user.getExternalId())
                    || !ids.contains(user.getId())) return null;
        }
        // This preserves the existing signed-session access-mode check, including
        // its live project-membership query and verified local-recovery semantics.
        if (!isAllowed(payload)) return Collections.emptySet();
        return identities(payload);
    }

    /** Validate live provider identities before using groups for this principal. */
    public Set<Identity> validateAccountIdentities(Account account, Set<Identity> identities) {
        boolean foundOwner = false;
        for (Identity identity : identities) {
            if (identity.getUser() || Strings.CS.equals(identity.getExternalIdType(), userType())) {
                if (!Strings.CS.equals(identity.getExternalIdType(), userType())
                        || !ownsExternalUser(account, identity)) {
                    throw ownerMismatch();
                }
                foundOwner = true;
            }
        }
        if (!foundOwner) throw ownerMismatch();
        Set<Identity> result = new HashSet<>(identities);
        addStableAccountIdentities(account, result);
        isAllowed(identitiesToIdList(result), result);
        return result;
    }

    private boolean ownsExternalUser(Account account, Identity user) {
        if (account == null || user == null || StringUtils.isBlank(user.getExternalId())
                || !Strings.CS.equals(user.getExternalIdType(), userType())) return false;
        if (Strings.CS.equals(account.getExternalIdType(), user.getExternalIdType())
                && Strings.CS.equals(account.getExternalId(), user.getExternalId())) return true;
        for (Credential link : authDao.getIdentityLinks(account.getId())) {
            Map<String, Object> data = link.getData();
            if (linkMatchesProvider(data, SecurityConstants.AUTH_PROVIDER.get())
                    && Strings.CS.equals(String.valueOf(data.get("externalIdType")), user.getExternalIdType())
                    && Strings.CS.equals(String.valueOf(data.get("externalId")), user.getExternalId())) return true;
        }
        return false;
    }

    private ClientVisibleException ownerMismatch() {
        return new ClientVisibleException(ResponseCodes.FORBIDDEN, "OwnerPermissionDenied",
                "The authentication identities do not belong to the key owner.", null);
    }

    @Override
    public boolean createAccount() {
        return true;
    }

    @Override
    protected boolean isWhitelisted(List<String> idList) {
        if (idList == null || idList.isEmpty()) {
            return false;
        }
        List<String> whitelistedValues = fromSeparatedString(ServiceAuthConstants.ALLOWED_IDENTITIES.get(), identitySeparator());

        for (String id : idList) {
            for (String whiteId: whitelistedValues){
                if (Strings.CS.equals(id, whiteId)){
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected boolean isAllowed(Map<String, Object> jsonData) {
        if (isVerifiedLocalRecoveryToken(jsonData)) {
            return true;
        }
        return super.isAllowed(jsonData);
    }

    public List<String> fromSeparatedString(String identities, String identitySeparator) {
        if (StringUtils.isEmpty(identities)) {
            return new ArrayList<>();
        }
        List<String> strings = new ArrayList<>();
        String[] splitted = identities.split(identitySeparator);
        for (String aSplitted : splitted) {
            String element = aSplitted.trim();
            strings.add(element);
        }
        return strings;
    }

    @Override
    public String tokenType() {
        return "externaljwt";
    }

    @Override
    public String getName() {
        return "";
    }
}
