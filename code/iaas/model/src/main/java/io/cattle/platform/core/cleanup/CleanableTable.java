package io.cattle.platform.core.cleanup;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.jooq.Field;
import org.jooq.Table;

public class CleanableTable {

    public static final List<String> TIMESTAMP_FIELD_NAME_PRECEDENCE = Arrays.asList(
            "removed",
            "end_time",
            "applied_updated",
            "expires",
            "created");

    public final Table<?> table;
    public final Field<?> idField;
    public final Field<?> removeField;
    public final boolean referenceCheckOnly;

    private Integer rowsDeleted = 0;
    private Integer rowsSkipped = 0;

    private CleanableTable(Table<?> table, boolean refCheckOnly) {
        this(table, null, refCheckOnly);
    }

    private CleanableTable(Table<?> table, Field<Date> removedField, boolean refCheckOnly) {
        this.table = table;
        this.idField = getIdField(table);
        this.removeField = removedField == null ? getRemoveField(table) : removedField;
        this.referenceCheckOnly = refCheckOnly;
    }

    private Field<?> getIdField(Table<?> table) {
        for (Field<?> field : table.fields()) {
            if (field.getName().equals("id")) {
                requireFieldType(field, Long.class);
                return field;
            }
        }
        return null;
    }

    private Field<?> getRemoveField(Table<?> table) {
        for (String fieldName : TIMESTAMP_FIELD_NAME_PRECEDENCE) {
            for (Field<?> field : table.fields()) {
                if (fieldName.equals(field.getName())) {
                    requireFieldType(field, Date.class);
                    return field;
                }
            }
        }
        return null;
    }

    static void requireFieldType(Field<?> field, Class<?> type) {
        if (!type.isAssignableFrom(field.getType())) {
            throw new IllegalArgumentException("Expected [" + field + "] to use [" + type.getName()
                    + "] but found [" + field.getType().getName() + "]");
        }
    }

    public void clearRowCounts() {
        rowsDeleted = 0;
        rowsSkipped = 0;
    }

    public Integer getRowsDeleted() {
        return rowsDeleted;
    }

    public void addRowsDeleted(Integer rowsDeleted) {
        this.rowsDeleted += rowsDeleted;
    }

    public Integer getRowsSkipped() {
        return rowsSkipped;
    }

    public void addRowsSkipped(Integer rowsSkipped) {
        this.rowsSkipped += rowsSkipped;
    }

    @Override
    public String toString() {
        return " " + table.toString();
    }

    public static CleanableTable from(Table<?> table) {
        return new CleanableTable(table, false);
    }

    public static CleanableTable from(Table<?> table, Field<Date> removedField) {
        return new CleanableTable(table, removedField, false);
    }

    public static CleanableTable forReference(Table<?> table) {
        return new CleanableTable(table, true);
    }
}
