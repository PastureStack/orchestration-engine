package io.cattle.platform.object.jooq.utils;

import io.cattle.platform.object.meta.ObjectMetaDataManager;
import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;

import java.util.List;
import java.util.Map;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.ForeignKey;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.TableRecord;
import org.jooq.UniqueKey;
import org.jooq.UpdatableRecord;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JooqUtils {

    private static final Logger log = LoggerFactory.getLogger(JooqUtils.class);

    public static <T extends UpdatableRecord<?>> T findById(DSLContext context, Class<T> clz, Object id) {
        return clz.cast(findRecordById(context, clz, id));
    }

    public static UpdatableRecord<?> findRecordById(DSLContext context, Class<?> clz, Object id) {
        if (id == null)
            return null;

        Table<?> table = getTableFromRecordClass(clz);
        if (table == null)
            return null;

        UniqueKey<?> key = table.getPrimaryKey();
        if (key == null || key.getFieldsArray().length != 1)
            return null;

        TableField<?, ?> keyField = key.getFieldsArray()[0];

        return UpdatableRecord.class.cast(context.selectFrom(table).where(primaryKeyCondition(keyField, id)).fetchOne());
    }

    private static <R extends org.jooq.Record, T> org.jooq.Condition primaryKeyCondition(TableField<R, T> keyField, Object id) {
        return keyField.eq(DSL.val(id, keyField));
    }

    public static Table<?> getTable(SchemaFactory schemaFactory, Class<?> clz) {
        return getTableFromRecordClass(getRecordClass(schemaFactory, clz));
    }

    public static Table<?> getTableFromRecordClass(Class<?> clz) {
        if (clz == null)
            return null;

        if (TableRecord.class.isAssignableFrom(clz)) {
            try {
                TableRecord<?> record = TableRecord.class.cast(clz.getDeclaredConstructor().newInstance());
                return record.getTable();
            } catch (ReflectiveOperationException e) {
                log.error("Failed to determine table for [{}]", clz, e);
            }
        }
        return null;
    }

    public static Class<?> getRecordClass(SchemaFactory factory, Class<?> clz) {
        if (UpdatableRecord.class.isAssignableFrom(clz)) {
            return clz.asSubclass(UpdatableRecord.class);
        }

        if (factory != null) {
            Schema schema = factory.getSchema(clz);
            Class<?> testClz = factory.getSchemaClass(schema.getId());
            if (clz.isAssignableFrom(testClz)) {
                if (!UpdatableRecord.class.isAssignableFrom(testClz)) {
                    throw new IllegalArgumentException("Class [" + testClz + "] is not an instanceof UpdatableRecord");
                }
                return testClz.asSubclass(UpdatableRecord.class);
            }
        }

        throw new IllegalArgumentException("Failed to find UpdatableRecord class for [" + clz + "]");
    }

    public static UpdatableRecord<?> getRecord(Class<?> clz) {
        try {
            return UpdatableRecord.class.cast(clz.getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate [" + clz + "]", e);
        }
    }

    public static UpdatableRecord<?> getRecordObject(Object obj) {
        if (obj == null)
            return null;

        if (obj instanceof UpdatableRecord<?>) {
            return UpdatableRecord.class.cast(obj);
        }
        throw new IllegalArgumentException("Expected instance of [" + UpdatableRecord.class + "] got [" + obj.getClass() + "]");
    }

    public static <T extends UpdatableRecord<?>> T getRecordObject(Object obj, Class<T> type) {
        return type.cast(getRecordObject(obj));
    }

    public static org.jooq.Condition toConditions(ObjectMetaDataManager metaData, String type, Map<Object, Object> criteria) {
        org.jooq.Condition existingCondition = null;

        for (Map.Entry<Object, Object> entry : criteria.entrySet()) {
            Object value = entry.getValue();
            Object key = entry.getKey();
            TableField<?, ?> field = null;
            if (key == org.jooq.Condition.class) {
                if (!(value instanceof org.jooq.Condition)) {
                    throw new IllegalArgumentException("If key is Condition, value must be an instanceof Condition got key [" + key +
                            "] value [" + value + "]");
                }
            } else {
                field = getTableField(metaData, type, key);
                if (field == null) {
                    continue;
                }
            }

            org.jooq.Condition newCondition = null;

            if (value instanceof org.jooq.Condition) {
                newCondition = (org.jooq.Condition) value;
            } else if (value instanceof Condition) {
                newCondition = toCondition(field, (Condition) value);
            } else if (value instanceof List) {
                newCondition = listToCondition(field, (List<?>) value);
            } else if (value == null) {
                newCondition = field.isNull();
            } else {
                newCondition = fieldEqualsValue(field, value);
            }

            if (existingCondition == null) {
                existingCondition = newCondition;
            } else {
                existingCondition = existingCondition.and(newCondition);
            }
        }

        return existingCondition;
    }

    public static TableField<?, ?> getTableField(ObjectMetaDataManager metaData, String type, Object key) {
        Object objField = metaData.convertFieldNameFor(type, key);
        if (objField instanceof TableField<?, ?> field) {
            return field;
        } else {
            return null;
        }
    }

    public static org.jooq.Condition fieldEquals(Field<?> left, Field<?> right) {
        return DSL.condition("{0} = {1}", left, right);
    }

    public static org.jooq.Condition fieldEqualsValue(TableField<?, ?> field, Object value) {
        return equalValue(field, value);
    }

    public static org.jooq.Condition fieldNotEqualsValue(TableField<?, ?> field, Object value) {
        return notEqualValue(field, value);
    }

    private static <R extends org.jooq.Record, T> org.jooq.Condition equalValue(TableField<R, T> field, Object value) {
        return field.eq(DSL.val(value, field));
    }

    /**
     * Determines if a table's primary key is referenced at all by other tables.
     * 
     * @param table
     * @param others
     * @return
     */
    public static boolean isReferencedBy(Table<?> table, List<Table<?>> others) {
      for (Table<?> other : others) {
        for (ForeignKey<?, ?> key : other.getReferences()) {
          if (key.getKey().getTable().equals(table)) {
            return true;
          }
        }
      }
      return false;
    }

    protected static org.jooq.Condition listToCondition(TableField<?, ?> field, List<?> list) {
        org.jooq.Condition condition = null;
        for (Object value : list) {
            if (value instanceof Condition) {
                org.jooq.Condition newCondition = toCondition(field, (Condition) value);
                condition = condition == null ? newCondition : condition.and(newCondition);
            } else {
                org.jooq.Condition newCondition = fieldEqualsValue(field, value);
                condition = condition == null ? newCondition : condition.and(newCondition);
            }
        }

        return condition;
    }

    protected static org.jooq.Condition toCondition(TableField<?, ?> field, Condition value) {
        Condition condition = value;
        switch (condition.getConditionType()) {
        case EQ:
            return fieldEqualsValue(field, condition.getValue());
        case GT:
            return greaterThanValue(field, condition.getValue());
        case GTE:
            return greaterOrEqualValue(field, condition.getValue());
        case IN:
            List<Object> values = condition.getValues();
            if (values.size() == 1) {
                return fieldEqualsValue(field, values.get(0));
            } else {
                return field.in(condition.getValues());
            }
        case NOTIN:
            List<Object> vals = condition.getValues();
            if (vals.size() == 1) {
                return notEqualValue(field, vals.get(0));
            } else {
                return field.notIn(condition.getValues());
            }
        case LIKE:
            return field.like(condition.getValue().toString());
        case LT:
            return lessThanValue(field, condition.getValue());
        case LTE:
            return lessOrEqualValue(field, condition.getValue());
        case NE:
            return notEqualValue(field, condition.getValue());
        case NOTLIKE:
            return field.notLike(condition.getValue().toString());
        case NOTNULL:
            return field.isNotNull();
        case NULL:
            return field.isNull();
        case PREFIX:
            return field.like(condition.getValue() + "%");
        case OR:
            return toCondition(field, condition.getLeft()).or(toCondition(field, condition.getRight()));
        default:
            throw new IllegalArgumentException("Invalid condition type [" + condition.getConditionType() + "]");
        }
    }

    private static <R extends org.jooq.Record, T> org.jooq.Condition notEqualValue(TableField<R, T> field, Object value) {
        return field.ne(DSL.val(value, field));
    }

    private static <R extends org.jooq.Record, T> org.jooq.Condition greaterThanValue(TableField<R, T> field, Object value) {
        return field.gt(DSL.val(value, field));
    }

    private static <R extends org.jooq.Record, T> org.jooq.Condition greaterOrEqualValue(TableField<R, T> field, Object value) {
        return field.ge(DSL.val(value, field));
    }

    private static <R extends org.jooq.Record, T> org.jooq.Condition lessThanValue(TableField<R, T> field, Object value) {
        return field.lt(DSL.val(value, field));
    }

    private static <R extends org.jooq.Record, T> org.jooq.Condition lessOrEqualValue(TableField<R, T> field, Object value) {
        return field.le(DSL.val(value, field));
    }

}
