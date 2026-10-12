package io.cattle.platform.iaas.api.manager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;

import org.junit.Test;

public class HaConfigSettingsTest {

    @Test
    public void mysqlCommandsUseConfiguredConnectionWithoutPasswordArguments() throws Exception {
        StubHaConfigSettings settings = new StubHaConfigSettings("mysql");
        settings.port = "3307";
        settings.name = "qa database";
        settings.user = "export_reader";
        settings.password = "synthetic password ' \\ \n";
        HaConfigManager manager = new HaConfigManager(settings);

        assertEquals(Arrays.asList("/usr/bin/mysql", "--protocol=TCP", "--host=db.example", "--port=3307", "--user=export_reader",
                "--skip-column-names", "-s", "--database=qa database", "-e",
                "SELECT SUM(data_length)/power(1024,2) AS dbsize_mb FROM information_schema.tables WHERE table_schema=DATABASE() GROUP BY table_schema;"),
                manager.dbSizeProcessBuilder().command());
        assertEquals(Arrays.asList("/usr/bin/mysqldump", "--protocol=TCP", "--host=db.example", "--port=3307", "--user=export_reader",
                "--", "qa database"), manager.dbDumpProcessBuilder().command());
        for (ProcessBuilder builder : Arrays.asList(manager.dbSizeProcessBuilder(), manager.dbDumpProcessBuilder())) {
            assertEquals(settings.password, builder.environment().get("MYSQL_PWD"));
            assertFalse(builder.command().toString().contains(settings.password));
            assertFalse(builder.command().contains("-uroot"));
        }
    }

    @Test
    public void mysqlInvalidConnectionSettingsDoNotFallBackToRoot() throws Exception {
        for (String port : new String[] { null, "", "0", "65536", "3306 --user=root" }) {
            StubHaConfigSettings settings = new StubHaConfigSettings("mysql");
            settings.port = port;
            assertInvalid(settings);
        }
        StubHaConfigSettings settings = new StubHaConfigSettings("mysql");
        settings.user = "";
        assertInvalid(settings);
        settings.user = "cattle";
        settings.password = null;
        assertInvalid(settings);
        settings.password = "synthetic\0password";
        assertInvalid(settings);
    }

    private void assertInvalid(StubHaConfigSettings settings) throws Exception {
        try {
            new HaConfigManager(settings).dbDumpProcessBuilder();
            fail("Expected invalid connection settings");
        } catch (IOException expected) {
            assertEquals("Invalid database connection settings", expected.getMessage());
            assertTrue(expected.getCause() == null);
        }
    }

    @Test
    public void postgresCommandsRemainCompatible() throws Exception {
        HaConfigManager manager = new HaConfigManager(new StubHaConfigSettings("postgres"));

        assertEquals(Arrays.asList("/usr/bin/psql", "cattle", "cattle", "-t", "-q", "-c",
                "SELECT pg_database_size('cattle')/power(1024,2)"),
                manager.dbSizeProcessBuilder().command());
        assertEquals(Arrays.asList("/usr/bin/pg_dump", "-Fc", "-Ucattle", "cattle"), manager.dbDumpProcessBuilder().command());
    }

    @Test
    public void unknownDbTypeKeepsPostgresFallbackBehavior() throws Exception {
        HaConfigManager manager = new HaConfigManager(new StubHaConfigSettings("sqlite"));

        assertEquals("/usr/bin/psql", manager.dbSizeProcessBuilder().command().get(0));
        assertEquals("/usr/bin/pg_dump", manager.dbDumpProcessBuilder().command().get(0));
    }

    static class StubHaConfigSettings implements HaConfigSettings {

        private final String dbType;
        String port = "3306", name = "cattle", user = "cattle", password = "password";

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
            return port;
        }

        @Override
        public String dbName() {
            return name;
        }

        @Override
        public String dbUser() {
            return user;
        }

        @Override
        public String dbPassword() {
            return password;
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
