package io.cattle.platform.iaas.api.manager;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class HaConfigSettingsTest {

    @Test
    public void mysqlCommandsRemainCompatible() {
        HaConfigManager manager = new HaConfigManager(new StubHaConfigSettings("mysql"));

        assertEquals(Arrays.asList("mysql", "--skip-column-names", "-s", "-uroot", "-e",
                "SELECT SUM(data_length)/power(1024,2) AS dbsize_mb FROM information_schema.tables WHERE table_schema='cattle' GROUP BY table_schema;"),
                manager.dbSizeProcessBuilder().command());
        assertEquals(Arrays.asList("mysqldump", "-uroot", "cattle"), manager.dbDumpProcessBuilder().command());
    }

    @Test
    public void postgresCommandsRemainCompatible() {
        HaConfigManager manager = new HaConfigManager(new StubHaConfigSettings("postgres"));

        assertEquals(Arrays.asList("psql", "cattle", "cattle", "-t", "-q", "-c",
                "SELECT pg_database_size('cattle')/power(1024,2)"),
                manager.dbSizeProcessBuilder().command());
        assertEquals(Arrays.asList("pg_dump", "-Fc", "-Ucattle", "cattle"), manager.dbDumpProcessBuilder().command());
    }

    @Test
    public void unknownDbTypeKeepsPostgresFallbackBehavior() {
        HaConfigManager manager = new HaConfigManager(new StubHaConfigSettings("sqlite"));

        assertEquals("psql", manager.dbSizeProcessBuilder().command().get(0));
        assertEquals("pg_dump", manager.dbDumpProcessBuilder().command().get(0));
    }

    private static class StubHaConfigSettings implements HaConfigSettings {

        private final String dbType;

        StubHaConfigSettings(String dbType) {
            this.dbType = dbType;
        }

        @Override
        public String dbType() {
            return dbType;
        }

        @Override
        public String dbHost() {
            return "db.example";
        }

        @Override
        public String dbPort() {
            return "3306";
        }

        @Override
        public String dbName() {
            return "cattle";
        }

        @Override
        public String dbUser() {
            return "cattle";
        }

        @Override
        public String dbPassword() {
            return "password";
        }

        @Override
        public boolean haEnabled() {
            return true;
        }

        @Override
        public int haClusterSize() {
            return 3;
        }
    }
}
