package io.cattle.platform.iaas.api.auth.mfa;

import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;

import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.object.ObjectManager;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;

public class MfaDaoImpl extends AbstractJooqDao implements MfaDao {

    @Inject
    ObjectManager objectManager;

    @Override
    public Credential create(long accountId, String kind, String publicValue, String secretValue,
                             Map<String, Object> data) {
        if (accountId <= 0 || StringUtils.isAnyBlank(kind, publicValue)) {
            throw new IllegalArgumentException("Account, credential kind, and public value are required");
        }
        Map<Object, Object> properties = new HashMap<>();
        properties.put(CREDENTIAL.ACCOUNT_ID, accountId);
        properties.put(CREDENTIAL.KIND, kind);
        properties.put(CREDENTIAL.PUBLIC_VALUE, publicValue);
        properties.put(CREDENTIAL.SECRET_VALUE, secretValue);
        properties.put(CREDENTIAL.STATE, CommonStatesConstants.ACTIVE);
        properties.put(CREDENTIAL.DATA, data == null ? new HashMap<String, Object>() : new HashMap<>(data));
        /*
         * MFA challenges are also created during the unauthenticated half of
         * login.  The legacy object layer assigns new records to the request's
         * transient token account in that context, even when account_id was
         * supplied explicitly.  Correct the internal record immediately to
         * the already verified authorization principal.  Account reassignment
         * through persist is the same path used for identity-link migration.
         */
        Credential created = objectManager.create(Credential.class,
                objectManager.convertToPropertiesFor(Credential.class, properties));
        if (!Long.valueOf(accountId).equals(created.getAccountId())) {
            created.setAccountId(accountId);
            created = objectManager.persist(created);
        }
        if (!Long.valueOf(accountId).equals(created.getAccountId())) {
            throw new IllegalStateException("Unable to assign the MFA credential to its account");
        }
        return created;
    }

    @Override
    public Credential findActive(String kind, String publicValue) {
        if (StringUtils.isAnyBlank(kind, publicValue)) {
            return null;
        }
        return create()
                .selectFrom(CREDENTIAL)
                .where(CREDENTIAL.KIND.eq(kind)
                        .and(CREDENTIAL.PUBLIC_VALUE.eq(publicValue))
                        .and(CREDENTIAL.STATE.eq(CommonStatesConstants.ACTIVE))
                        .and(CREDENTIAL.REMOVED.isNull()))
                .orderBy(CREDENTIAL.ID.asc())
                .limit(1)
                .fetchOne();
    }

    @Override
    public List<? extends Credential> listActive(long accountId, String... kinds) {
        if (accountId <= 0 || kinds == null || kinds.length == 0) {
            return java.util.Collections.emptyList();
        }
        return create()
                .selectFrom(CREDENTIAL)
                .where(CREDENTIAL.ACCOUNT_ID.eq(accountId)
                        .and(CREDENTIAL.KIND.in(Arrays.asList(kinds)))
                        .and(CREDENTIAL.STATE.eq(CommonStatesConstants.ACTIVE))
                        .and(CREDENTIAL.REMOVED.isNull()))
                .orderBy(CREDENTIAL.ID.asc())
                .fetch();
    }

    @Override
    public List<? extends Credential> listActiveByKind(String kind) {
        if (StringUtils.isBlank(kind)) {
            return java.util.Collections.emptyList();
        }
        return create()
                .selectFrom(CREDENTIAL)
                .where(CREDENTIAL.KIND.eq(kind)
                        .and(CREDENTIAL.STATE.eq(CommonStatesConstants.ACTIVE))
                        .and(CREDENTIAL.REMOVED.isNull()))
                .orderBy(CREDENTIAL.ID.asc())
                .fetch();
    }

    @Override
    public Credential save(Credential credential, String secretValue, Map<String, Object> data) {
        if (credential == null) {
            throw new IllegalArgumentException("Credential is required");
        }
        if (secretValue != null) {
            credential.setSecretValue(secretValue);
        }
        if (data != null) {
            credential.setData(new HashMap<>(data));
        }
        objectManager.persist(credential);
        return objectManager.reload(credential);
    }

    @Override
    public void deactivate(Credential credential, String reason) {
        if (credential == null) {
            return;
        }
        Map<String, Object> data = credential.getData() == null
                ? new HashMap<String, Object>()
                : new HashMap<>(credential.getData());
        data.put("deactivatedAt", new Date());
        if (StringUtils.isNotBlank(reason)) {
            data.put("deactivationReason", reason);
        }
        credential.setData(data);
        credential.setState(CommonStatesConstants.INACTIVE);
        objectManager.persist(credential);
    }
}
