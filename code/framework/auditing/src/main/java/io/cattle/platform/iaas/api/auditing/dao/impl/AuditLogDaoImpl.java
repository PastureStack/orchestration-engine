package io.cattle.platform.iaas.api.auditing.dao.impl;

import io.cattle.platform.api.auth.Identity;
import io.cattle.platform.core.model.AuditLog;
import io.cattle.platform.core.model.tables.AuditLogTable;
import io.cattle.platform.core.model.tables.AccountTable;
import io.cattle.platform.core.model.tables.records.AuditLogRecord;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.iaas.api.auditing.dao.AuditLogDao;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataUtils;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.HashMap;
import java.util.Map;
import java.util.Date;
import jakarta.inject.Inject;


public class AuditLogDaoImpl extends AbstractJooqDao implements AuditLogDao {

    @Inject
    ObjectManager objectManager;

    @Override
    public AuditLog createDelegatedOnce(String eventId, String resourceType, Long resourceId, Map<String, Object> data,
            Identity identity, Long accountId, Long authenticatedAsAccountId, String eventType, String authType,
            Long runTime, String description, String clientIp) {
        if (eventId == null || !eventId.matches("[a-f0-9]{64}")) {
            throw new IllegalStateException("Invalid delegated outcome identity");
        }
        // audit_log has no UUID/unique event column. The account row lock is
        // shared by all servers, unlike a possibly in-memory LockProvider.
        String terminalType = "api.stream.terminal." + eventId;
        return create().transactionResult(transaction -> {
            DSLContext database = DSL.using(transaction);
            Long principal = database.select(AccountTable.ACCOUNT.ID).from(AccountTable.ACCOUNT)
                    .where(AccountTable.ACCOUNT.ID.eq(authenticatedAsAccountId)).forUpdate().fetchOne(AccountTable.ACCOUNT.ID);
            if (principal == null) throw new IllegalStateException("Delegated audit principal is unavailable");
            AuditLog existing = database.selectFrom(AuditLogTable.AUDIT_LOG)
                    .where(AuditLogTable.AUDIT_LOG.EVENT_TYPE.eq(terminalType)).fetchOne();
            if (existing != null) return existing; // First terminal result is immutable.
            Map<String, Object> dataMap = new HashMap<>();
            dataMap.put(DataUtils.FIELDS, data);
            // Keep the normal kind/created fields (this table has no state or
            // removed column), using one transaction and no ObjectManager.
            database.insertInto(AuditLogTable.AUDIT_LOG,
                    AuditLogTable.AUDIT_LOG.ACCOUNT_ID, AuditLogTable.AUDIT_LOG.AUTHENTICATED_AS_ACCOUNT_ID,
                    AuditLogTable.AUDIT_LOG.EVENT_TYPE, AuditLogTable.AUDIT_LOG.DATA, AuditLogTable.AUDIT_LOG.KIND,
                    AuditLogTable.AUDIT_LOG.CREATED, AuditLogTable.AUDIT_LOG.AUTHENTICATED_AS_IDENTITY_ID,
                    AuditLogTable.AUDIT_LOG.RUNTIME, AuditLogTable.AUDIT_LOG.DESCRIPTION, AuditLogTable.AUDIT_LOG.AUTH_TYPE,
                    AuditLogTable.AUDIT_LOG.RESOURCE_ID, AuditLogTable.AUDIT_LOG.RESOURCE_TYPE, AuditLogTable.AUDIT_LOG.CLIENT_IP)
                    .values(accountId, authenticatedAsAccountId, terminalType, dataMap, "auditLog", new Date(),
                            identity == null ? null : identity.getId(), runTime, description, authType, resourceId, resourceType, clientIp)
                    .execute();
            return database.selectFrom(AuditLogTable.AUDIT_LOG).where(AuditLogTable.AUDIT_LOG.EVENT_TYPE.eq(terminalType)).fetchOne();
        });
    }

    @Override
    public AuditLog create(String resourceType, Long resourceId, Map<String, Object> data, Identity identity,
                           Long accountId, Long authenticatedAsAccountId, String eventType, String authType, Long runTime,
                           String description, String clientIp) {
        AuditLog logs = newAuditLog(resourceType, resourceId, data, identity, accountId, authenticatedAsAccountId,
                eventType, authType, runTime, description, clientIp);
        objectManager.create(logs);
        return objectManager.reload(logs);
    }

    @Override
    public AuditLog createApiKeyEvent(String resourceType, Long resourceId, Map<String, Object> data, Identity identity,
            Long accountId, Long authenticatedAsAccountId, String eventType, String authType, Long runTime,
            String description, String clientIp) {
        // These account fields came from the verified event, not the request
        // which happens to replay the outbox. ObjectManager post-init would
        // replace them with that unrelated request's ApiContext policy.
        var logs = newAuditLog(resourceType, resourceId, data, identity, accountId, authenticatedAsAccountId,
                eventType, authType, runTime, description, clientIp);
        logs.setKind("auditLog");
        logs.setCreated(new Date());
        logs.store();
        return logs;
    }

    private AuditLogRecord newAuditLog(String resourceType,
            Long resourceId, Map<String, Object> data, Identity identity, Long accountId, Long authenticatedAsAccountId,
            String eventType, String authType, Long runTime, String description, String clientIp) {
        var logs = create().newRecord(AuditLogTable.AUDIT_LOG);
        logs.setAccountId(accountId);
        logs.setAuthenticatedAsAccountId(authenticatedAsAccountId);
        logs.setEventType(eventType);
        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put(DataUtils.FIELDS, data);
        logs.setData(dataMap);
        logs.setAuthenticatedAsIdentityId(identity != null ? identity.getId() : null);
        logs.setRuntime(runTime);
        logs.setDescription(description);
        logs.setAuthType(authType);
        logs.setResourceId(resourceId);
        logs.setResourceType(resourceType);
        logs.setClientIp(clientIp);
        return logs;
    }
}
