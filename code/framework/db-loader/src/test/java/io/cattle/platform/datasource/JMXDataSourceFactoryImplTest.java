package io.cattle.platform.datasource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.apache.commons.dbcp2.BasicDataSource;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class JMXDataSourceFactoryImplTest {

    @Test
    public void createsDbcp2DataSourceWithJmxName() {
        final String key = "dbcp.jmx.prefix";

        try {
            ConfigurationManager.getConfigInstance().setProperty(key, "org.apache.commons.dbcp2:name=");

            TestJMXDataSourceFactory factory = new TestJMXDataSourceFactory();
            BasicDataSource dataSource = factory.create("cattle");

            assertTrue(dataSource instanceof org.apache.commons.dbcp2.BasicDataSource);
            assertEquals("org.apache.commons.dbcp2:name=cattle", dataSource.getJmxName());
        } finally {
            clearProperty(key);
        }
    }

    @Test
    public void liquibaseDefaultsToCattleDataSourceAlias() {
        String[] keys = new String[] {
                "db.cattle.database",
                "db.cattle.h2.url",
                "db.liquibase.database",
                "db.liquibase.alias"
        };

        try {
            ConfigurationManager.getConfigInstance().setProperty("db.cattle.database", "h2");
            ConfigurationManager.getConfigInstance().setProperty("db.cattle.h2.url", "jdbc:h2:mem:liquibase-default-alias");

            DefaultDataSourceFactoryImpl factory = new DefaultDataSourceFactoryImpl();
            BasicDataSource dataSource = (BasicDataSource) factory.createDataSource("liquibase");

            assertEquals("jdbc:h2:mem:liquibase-default-alias", dataSource.getUrl());
        } finally {
            for (String key : keys) {
                clearProperty(key);
            }
        }
    }

    @Test
    public void configDefaultsToCattleDataSourceAlias() {
        String[] keys = new String[] {
                "db.cattle.database",
                "db.cattle.h2.url",
                "db.config.database",
                "db.config.alias"
        };

        try {
            ConfigurationManager.getConfigInstance().setProperty("db.cattle.database", "h2");
            ConfigurationManager.getConfigInstance().setProperty("db.cattle.h2.url", "jdbc:h2:mem:config-default-alias");

            DefaultDataSourceFactoryImpl factory = new DefaultDataSourceFactoryImpl();
            BasicDataSource dataSource = (BasicDataSource) factory.createDataSource("config");

            assertEquals("jdbc:h2:mem:config-default-alias", dataSource.getUrl());
        } finally {
            for (String key : keys) {
                clearProperty(key);
            }
        }
    }

    @Test
    public void mysqlUrlAggregatesConvenienceProperties() {
        String[] keys = new String[] {
                "db.cattle.database",
                "db.cattle.mysql.host",
                "db.cattle.mysql.port",
                "db.cattle.mysql.name",
                "db.cattle.mysql.url"
        };

        try {
            ConfigurationManager.getConfigInstance().setProperty("db.cattle.database", "mysql");
            ConfigurationManager.getConfigInstance().setProperty("db.cattle.mysql.host", "db.example.test");
            ConfigurationManager.getConfigInstance().setProperty("db.cattle.mysql.port", "3307");
            ConfigurationManager.getConfigInstance().setProperty("db.cattle.mysql.name", "cattle_test");
            ConfigurationManager.getConfigInstance().setProperty("db.cattle.mysql.url",
                    "jdbc:mysql://${db.cattle.mysql.host}:${db.cattle.mysql.port}/${db.cattle.mysql.name}");

            DefaultDataSourceFactoryImpl factory = new DefaultDataSourceFactoryImpl();
            BasicDataSource dataSource = (BasicDataSource) factory.createDataSource("cattle");

            assertEquals("jdbc:mysql://db.example.test:3307/cattle_test", dataSource.getUrl());
        } finally {
            for (String key : keys) {
                clearProperty(key);
            }
        }
    }

    private void clearProperty(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    static class TestJMXDataSourceFactory extends JMXDataSourceFactoryImpl {

        BasicDataSource create(String name) {
            return newBasicDataSource(name);
        }

    }

}
