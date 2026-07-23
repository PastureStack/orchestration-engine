package io.cattle.platform.core.dao.impl;

import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.dao.GenericMapDao;
import io.cattle.platform.object.jooq.utils.JooqUtils;
import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.cattle.platform.object.meta.Relationship;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.jooq.Table;
import org.jooq.TableField;

@Named
public class GenericMapDaoImpl extends AbstractCoreDao implements GenericMapDao {

    SchemaFactory schemaFactory;
    ObjectMetaDataManager metaDataManager;

    @Override
    public <T> List<? extends T> findNonRemoved(Class<T> mapType, Class<?> resourceType, long resourceId) {
        String type = schemaFactory.getSchemaName(mapType);

        Table<?> table = getTable(mapType);

        Relationship reference = getRelationship(mapType, resourceType);
        TableField<?, ?> removed = JooqUtils.getTableField(metaDataManager, type, ObjectMetaDataManager.REMOVED_FIELD);
        TableField<?, ?> referenceField = JooqUtils.getTableField(metaDataManager, type, reference.getPropertyName());

        if ( removed == null || referenceField == null ) {
            throw new IllegalArgumentException("Type [" + mapType + "] is missing required removed or reference column");
        }

        return castRecordList(mapType, create()
                .selectFrom(table)
                .where(
                        removed.isNull()
                        .and(JooqUtils.fieldEqualsValue(referenceField, resourceId)))
                .fetch());
    }


    @Override
    public <T> T findNonRemoved(Class<T> mapType, Class<?> leftResourceType, long leftResourceId,
            Class<?> rightResourceType, long rightResourceId) {
        String type = schemaFactory.getSchemaName(mapType);

        Table<?> table = getTable(mapType);

        Relationship leftReference = getRelationship(mapType, leftResourceType);
        Relationship rightReference = getRelationship(mapType, rightResourceType);
        TableField<?, ?> removed = JooqUtils.getTableField(metaDataManager, type, ObjectMetaDataManager.REMOVED_FIELD);
        TableField<?, ?> leftReferenceField = JooqUtils.getTableField(metaDataManager, type, leftReference.getPropertyName());
        TableField<?, ?> rightReferenceField = JooqUtils.getTableField(metaDataManager, type, rightReference.getPropertyName());

        if ( removed == null || leftReferenceField == null || rightReferenceField == null ) {
            throw new IllegalArgumentException("Type [" + mapType + "] is missing required removed or references column");
        }

        return castRecord(mapType, create()
                .selectFrom(table)
                .where(
                        removed.isNull()
                        .and(JooqUtils.fieldEqualsValue(leftReferenceField, leftResourceId))
                        .and(JooqUtils.fieldEqualsValue(rightReferenceField, rightResourceId)))
                .fetchOne());
    }


    @Override
    public <T> List<? extends T> findToRemove(Class<T> mapType, Class<?> resourceType, long resourceId) {
        String type = schemaFactory.getSchemaName(mapType);

        Table<?> table = getTable(mapType);

        Relationship reference = getRelationship(mapType, resourceType);
        TableField<?, ?> removed = JooqUtils.getTableField(metaDataManager, type, ObjectMetaDataManager.REMOVED_FIELD);
        TableField<?, ?> referenceField = JooqUtils.getTableField(metaDataManager, type, reference.getPropertyName());
        TableField<?, ?> state = JooqUtils.getTableField(metaDataManager, type, ObjectMetaDataManager.STATE_FIELD);

        if ( removed == null || referenceField == null || state == null ) {
            throw new IllegalArgumentException("Type [" + mapType + "] is missing required removed, reference, or state column");
        }

        return castRecordList(mapType, create()
                .selectFrom(table)
                .where(
                        JooqUtils.fieldEqualsValue(referenceField, resourceId)
                        .and(
                                removed.isNull()
                                .or(JooqUtils.fieldEqualsValue(state, CommonStatesConstants.REMOVING))))
                .fetch());
    }

    @Override
    public <T> List<? extends T> findNonPurged(Class<T> mapType, Class<?> resourceType, long resourceId) {
        String type = schemaFactory.getSchemaName(mapType);

        Table<?> table = getTable(mapType);

        Relationship reference = getRelationship(mapType, resourceType);
        TableField<?, ?> referenceField = JooqUtils.getTableField(metaDataManager, type, reference.getPropertyName());
        TableField<?, ?> state = JooqUtils.getTableField(metaDataManager, type, ObjectMetaDataManager.STATE_FIELD);

        if ( referenceField == null || state == null ) {
            throw new IllegalArgumentException("Type [" + mapType + "] is missing required reference or state column");
        }

        return castRecordList(mapType, create()
                .selectFrom(table)
                .where(
                JooqUtils.fieldEqualsValue(referenceField, resourceId)
                .and(JooqUtils.fieldNotEqualsValue(state, CommonStatesConstants.PURGED)))
                .fetch());
    }

    protected <T> List<? extends T> castRecordList(Class<T> mapType, List<?> records) {
        List<T> result = new ArrayList<T>(records.size());
        for (Object record : records) {
            result.add(mapType.cast(record));
        }
        return result;
    }

    protected <T> T castRecord(Class<T> mapType, Object record) {
        if (record == null) {
            return null;
        }
        return mapType.cast(record);
    }

    protected <T> Table<?> getTable(Class<?> mapType) {
        Class<?> record = JooqUtils.getRecordClass(schemaFactory, mapType);
        return JooqUtils.getTableFromRecordClass(record);
    }

    protected Relationship getRelationship(Class<?> mapType, Class<?> resourceType) {
        Map<String,Relationship> rels = metaDataManager.getLinkRelationships(schemaFactory, schemaFactory.getSchemaName(mapType));
        Relationship reference = null;
        for ( Map.Entry<String,Relationship> entry : rels.entrySet() ) {
            Relationship rel = entry.getValue();
            if ( rel.getRelationshipType() == Relationship.RelationshipType.REFERENCE && resourceType.isAssignableFrom(rel.getObjectType())) {
                reference = rel;
                break;
            }
        }

        if ( reference == null ) {
            throw new IllegalArgumentException("Failed to find reference relationship from [" + mapType + "] to [" + resourceType + "]");
        }

        return reference;
    }

    public SchemaFactory getSchemaFactory() {
        return schemaFactory;
    }

    @Inject
    @Named("CoreSchemaFactory")
    public void setSchemaFactory(SchemaFactory schemaFactory) {
        this.schemaFactory = schemaFactory;
    }

    public ObjectMetaDataManager getMetaDataManager() {
        return metaDataManager;
    }

    @Inject
    public void setMetaDataManager(ObjectMetaDataManager metaDataManager) {
        this.metaDataManager = metaDataManager;
    }

    @Override
    public <T> T findToRemove(Class<T> mapType, Class<?> leftResourceType, long leftResourceId,
            Class<?> rightResourceType, long rightResourceId) {
        String type = schemaFactory.getSchemaName(mapType);

        Table<?> table = getTable(mapType);

        Relationship leftReference = getRelationship(mapType, leftResourceType);
        Relationship rightReference = getRelationship(mapType, rightResourceType);
        TableField<?, ?> removed = JooqUtils.getTableField(metaDataManager, type,
                ObjectMetaDataManager.REMOVED_FIELD);
        TableField<?, ?> state = JooqUtils.getTableField(metaDataManager, type, ObjectMetaDataManager.STATE_FIELD);
        TableField<?, ?> leftReferenceField = JooqUtils.getTableField(metaDataManager, type,
                leftReference.getPropertyName());
        TableField<?, ?> rightReferenceField = JooqUtils.getTableField(metaDataManager, type,
                rightReference.getPropertyName());

        if (removed == null || leftReferenceField == null || rightReferenceField == null) {
            throw new IllegalArgumentException("Type [" + mapType
                    + "] is missing required removed or references column");
        }

        return castRecord(mapType, create()
                .selectFrom(table)
                .where(
                        (removed.isNull().or(JooqUtils.fieldEqualsValue(state, CommonStatesConstants.REMOVING)))
                                .and(JooqUtils.fieldEqualsValue(leftReferenceField, leftResourceId))
                                .and(JooqUtils.fieldEqualsValue(rightReferenceField, rightResourceId)))
                .fetchOne());
    }

}
