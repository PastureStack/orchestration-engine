package io.cattle.platform.liquibase;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.datasource.DataSourceFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import jakarta.inject.Inject;

import liquibase.Scope;
import liquibase.changelog.ChangeLogHistoryServiceFactory;
import liquibase.exception.LiquibaseException;
import liquibase.integration.spring.SpringLiquibase;

import org.jooq.Configuration;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Loader extends SpringLiquibase {

    private static final Set<SQLDialect> EMBEDDED = new HashSet<SQLDialect>(Arrays.asList(SQLDialect.H2, SQLDialect.HSQLDB));

    private static final String LOG_OPTION = "liquibase.databaseChangeLogTableName";
    private static final String LOCK_OPTION = "liquibase.databaseChangeLogLockTableName";

    private static final ConfigProperty<String> RELEASE_LOCK = ArchaiusUtil.getStringProperty("db.release.change.lock");
    private static final Logger log = LoggerFactory.getLogger("ConsoleStatus");

    Configuration configuration;
    String lockTable = "DATABASECHANGELOGLOCK";
    String changeLogTable = "DATABASECHANGELOG";
    DataSourceFactory dataSourceFactory;

    @Override
    public void afterPropertiesSet() throws LiquibaseException {
        if (getDataSource() == null && dataSourceFactory != null) {
            setDataSource(dataSourceFactory.createDataSource("liquibase"));
        }

        String oldLockTable = System.getProperty(LOCK_OPTION);
        String oldLogTable = System.getProperty(LOG_OPTION);
        try {
            try {
                System.setProperty(LOG_OPTION, changeLogTable);
                System.setProperty(LOCK_OPTION, lockTable);
                boolean release = false;
                if ("true".equals(RELEASE_LOCK.get())) {
                    release = true;
                } else if ("false".equals(RELEASE_LOCK.get())) {
                    release = false;
                } else {
                    release = isEmbedded(configuration.dialect());
                }

                if (release) {
                    DSL.using(getConfiguration()).delete(DSL.table(lockTable)).execute();
                }
            } catch (Throwable t) {
                // ignore errors
            }

            log.info("Starting DB migration");
            Scope.getCurrentScope().getSingleton(ChangeLogHistoryServiceFactory.class).register(new RancherChangeLogHistoryService());
            super.afterPropertiesSet();
            log.info("DB migration done");
        } finally {
            if (oldLogTable == null) {
                System.clearProperty(LOG_OPTION);
            } else {
                System.setProperty(LOG_OPTION, oldLogTable);
            }
            if (oldLockTable == null) {
                System.clearProperty(LOCK_OPTION);
            } else {
                System.setProperty(LOCK_OPTION, oldLockTable);
            }
        }
    }

    static boolean isEmbedded(SQLDialect dialect) {
        return EMBEDDED.contains(dialect) || "DERBY".equals(dialect.name());
    }

    @Inject
    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public String getLockTable() {
        return lockTable;
    }

    public void setLockTable(String lockTable) {
        this.lockTable = lockTable;
    }

    public String getChangeLogTable() {
        return changeLogTable;
    }

    public void setChangeLogTable(String changeLogTable) {
        this.changeLogTable = changeLogTable;
    }

    public DataSourceFactory getDataSourceFactory() {
        return dataSourceFactory;
    }

    public void setDataSourceFactory(DataSourceFactory dataSourceFactory) {
        this.dataSourceFactory = dataSourceFactory;
    }

}
