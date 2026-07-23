package io.cattle.platform.liquibase;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import liquibase.changelog.StandardChangeLogHistoryService;
import liquibase.database.Database;
import liquibase.exception.DatabaseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RancherChangeLogHistoryService extends StandardChangeLogHistoryService {

    private static final Logger log = LoggerFactory.getLogger(RancherChangeLogHistoryService.class);
    private static final Set<String> REQUIRED_CHANGELOG_COLUMNS = new LinkedHashSet<>(Arrays.asList(
            "ID",
            "AUTHOR",
            "FILENAME",
            "DATEEXECUTED",
            "ORDEREXECUTED",
            "EXECTYPE",
            "MD5SUM",
            "DESCRIPTION",
            "COMMENTS",
            "TAG",
            "LIQUIBASE",
            "CONTEXTS",
            "LABELS",
            "DEPLOYMENT_ID"));

    private boolean initialized;
    private TableRef changeLogTable;
    private TableRef changeLogLockTable;

    @Override
    public int getPriority() {
        return PRIORITY_DEFAULT + 1;
    }

    @Override
    public void init() throws DatabaseException {
        if (initialized) {
            return;
        }

        if (legacyChangeLogTablesExist()) {
            if (hasAllRequiredChangeLogColumns(changeLogTable)) {
                initialized = true;
                log.warn("Using JDBC verified legacy Rancher changelog tables without schema changes.");
                return;
            }
            ensureLegacyChangeLogColumns();
            initialized = true;
            log.warn("Using JDBC verified legacy Rancher changelog tables.");
            return;
        }

        try {
            super.init();
            initialized = true;
        } catch (DatabaseException e) {
            if (!isExistingChangeLogCreateFailure(e) || !legacyChangeLogTablesExist()) {
                throw e;
            }

            if (!hasAllRequiredChangeLogColumns(changeLogTable)) {
                ensureLegacyChangeLogColumns();
            }
            initialized = true;
            log.warn("Liquibase snapshot missed existing Rancher changelog tables; using JDBC verified legacy tables.");
        }
    }

    @Override
    public boolean hasDatabaseChangeLogTable() {
        try {
            return super.hasDatabaseChangeLogTable() || tableExists(getDatabaseChangeLogTableName());
        } catch (RuntimeException e) {
            if (tableExists(getDatabaseChangeLogTableName())) {
                return true;
            }
            throw e;
        }
    }

    @Override
    public void reset() {
        initialized = false;
        changeLogTable = null;
        changeLogLockTable = null;
        super.reset();
    }

    private boolean legacyChangeLogTablesExist() throws DatabaseException {
        changeLogTable = resolveTable(getDatabaseChangeLogTableName());
        changeLogLockTable = resolveTable(getDatabase().getDatabaseChangeLogLockTableName());
        return changeLogTable != null && changeLogLockTable != null;
    }

    private boolean tableExists(String tableName) {
        try {
            return tableExistsOrThrow(tableName);
        } catch (DatabaseException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean tableExistsOrThrow(String tableName) throws DatabaseException {
        return resolveTable(tableName) != null;
    }

    private int count(String table, String where, String... values) throws DatabaseException {
        String sql = "select count(*) from " + table + " where " + where;
        try (PreparedStatement statement = connection().prepareStatement(
                sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    private void ensureLegacyChangeLogColumns() throws DatabaseException {
        TableRef table = changeLogTableRef();
        ensureColumn(table, "DESCRIPTION", "varchar(255) null");
        ensureColumn(table, "COMMENTS", "varchar(255) null");
        ensureColumn(table, "TAG", "varchar(255) null");
        ensureColumn(table, "LIQUIBASE", "varchar(20) null");
        ensureColumn(table, "CONTEXTS", "varchar(255) null");
        ensureColumn(table, "LABELS", "varchar(255) null");
        ensureColumn(table, "DEPLOYMENT_ID", "varchar(10) null");
        ensureColumn(table, "ORDEREXECUTED", "int null");
        ensureColumn(table, "EXECTYPE", "varchar(10) null");
        ensureColumn(table, "MD5SUM", "varchar(35) null");
        execute("update " + table.sqlName() + " set ORDEREXECUTED = -1 where ORDEREXECUTED is null");
        execute("update " + table.sqlName() + " set EXECTYPE = 'EXECUTED' where EXECTYPE is null");
    }

    private void ensureColumn(TableRef table, String columnName, String definition) throws DatabaseException {
        if (count("information_schema.columns",
                "table_schema = ? and table_name = ? and upper(column_name) = upper(?)",
                table.schema, table.name, columnName) > 0) {
            return;
        }

        execute("alter table " + table.sqlName() + " add column `" + columnName + "` " + definition);
    }

    private boolean hasAllRequiredChangeLogColumns(TableRef table) throws DatabaseException {
        if (!verifyReadable(table)) {
            return false;
        }

        Set<String> columns = new LinkedHashSet<>();
        try (PreparedStatement statement = connection().prepareStatement(
                "select upper(column_name) from information_schema.columns where table_schema = ? and table_name = ?")) {
            statement.setString(1, table.schema);
            statement.setString(2, table.name);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    columns.add(result.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }

        return columns.containsAll(REQUIRED_CHANGELOG_COLUMNS);
    }

    private boolean verifyReadable(TableRef table) throws DatabaseException {
        try (Statement statement = connection().createStatement();
                ResultSet result = statement.executeQuery("select 1 from " + table.sqlName() + " limit 1")) {
            return true;
        } catch (SQLException e) {
            log.warn("Resolved Rancher changelog table {} but it is not readable.", table.sqlName(), e);
            return false;
        }
    }

    private void execute(String sql) throws DatabaseException {
        try (Statement statement = connection().createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            log.error("Failed to execute Rancher legacy Liquibase SQL: {}", sql, e);
            throw new DatabaseException(e);
        }
    }

    private String catalogOrSchema() throws DatabaseException {
        Database database = getDatabase();
        String schema = database.getLiquibaseSchemaName();
        if (schema != null && !schema.isEmpty()) {
            return schema;
        }

        String catalog = database.getLiquibaseCatalogName();
        if (catalog != null && !catalog.isEmpty()) {
            return catalog;
        }

        schema = database.getDefaultSchemaName();
        if (schema != null && !schema.isEmpty()) {
            return schema;
        }

        try (Statement statement = connection().createStatement();
                ResultSet result = statement.executeQuery("select database()")) {
            if (result.next()) {
                return result.getString(1);
            }
            return null;
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    private Connection connection() throws DatabaseException {
        Connection connection = getDatabase().getConnection().getUnderlyingConnection();
        if (connection == null) {
            throw new DatabaseException("Liquibase database connection does not expose a JDBC connection.");
        }
        return connection;
    }

    private TableRef changeLogTableRef() throws DatabaseException {
        if (changeLogTable == null && !legacyChangeLogTablesExist()) {
            throw new DatabaseException("Unable to find Rancher DATABASECHANGELOG table.");
        }
        return changeLogTable;
    }

    private TableRef resolveTable(String tableName) throws DatabaseException {
        String desiredTable = unqualifiedName(tableName);
        String owner = ownerFromQualifiedName(tableName);
        if (owner == null) {
            owner = catalogOrSchema();
        }
        if (owner != null) {
            TableRef table = queryTable(
                    "select table_schema, table_name from information_schema.tables "
                            + "where table_schema = ? and upper(table_name) = upper(?) limit 1",
                    owner, desiredTable);
            if (table != null) {
                return table;
            }
        }

        if (owner != null) {
            return queryTable(
                    "select table_schema, table_name from information_schema.tables "
                            + "where upper(table_name) = upper(?) "
                            + "and table_schema not in ('information_schema', 'mysql', 'performance_schema', 'sys') "
                            + "order by case when table_schema = ? then 0 else 1 end, table_schema limit 1",
                    desiredTable, owner);
        }

        return queryTable(
                "select table_schema, table_name from information_schema.tables "
                        + "where upper(table_name) = upper(?) "
                        + "and table_schema not in ('information_schema', 'mysql', 'performance_schema', 'sys') "
                        + "order by table_schema limit 1",
                desiredTable);
    }

    private TableRef queryTable(String sql, String... values) throws DatabaseException {
        try (PreparedStatement statement = connection().prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return new TableRef(result.getString(1), result.getString(2));
                }
                return null;
            }
        } catch (SQLException e) {
            throw new DatabaseException(e);
        }
    }

    private String unqualifiedName(String name) {
        String value = unquote(name);
        int dot = value.lastIndexOf('.');
        return dot == -1 ? value : value.substring(dot + 1);
    }

    private String ownerFromQualifiedName(String name) {
        String value = unquote(name);
        int dot = value.lastIndexOf('.');
        return dot == -1 ? null : value.substring(0, dot);
    }

    private String unquote(String name) {
        if (name == null) {
            return "";
        }
        return name.replace("`", "").trim();
    }

    private boolean isExistingChangeLogCreateFailure(Throwable throwable) {
        while (throwable != null) {
            String message = throwable.getMessage();
            if (message != null && message.contains("already exists")
                    && message.toUpperCase().contains(getDatabaseChangeLogTableName().toUpperCase())) {
                return true;
            }
            throwable = throwable.getCause();
        }
        return false;
    }

    private static class TableRef {
        private final String schema;
        private final String name;

        TableRef(String schema, String name) {
            this.schema = schema;
            this.name = name;
        }

        String sqlName() {
            return "`" + escape(schema) + "`.`" + escape(name) + "`";
        }

        private static String escape(String value) {
            return value.replace("`", "``");
        }
    }
}
