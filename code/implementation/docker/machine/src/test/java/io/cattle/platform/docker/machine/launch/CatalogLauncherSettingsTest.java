package io.cattle.platform.docker.machine.launch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.docker.machine.launch.CatalogLauncher.CatalogEntry;
import io.cattle.platform.docker.machine.launch.CatalogLauncher.ConfigFileFields;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class CatalogLauncherSettingsTest {

    private final JsonMapper jsonMapper = new JacksonJsonMapper();

    @Test
    public void commaCatalogUrlParsingPreservesDefaultBranchBehavior() throws Exception {
        CatalogLauncherSettings settings = new StubCatalogSettings()
                .withCatalogUrl(" library=https://git.example/library.git,community=https://git.example/community.git ")
                .withRancherServerVersion("v1.6.196");

        ConfigFileFields fields = renderConfig(settings);

        assertEquals("https://git.example/library.git", fields.getCatalogs().get("library").getUrl());
        assertEquals("master", fields.getCatalogs().get("library").getBranch());
        assertEquals("https://git.example/community.git", fields.getCatalogs().get("community").getUrl());
        assertEquals("master", fields.getCatalogs().get("community").getBranch());
    }

    @Test
    public void jsonCatalogUrlReleaseMarkerUsesMajorMinorReleaseBranch() throws Exception {
        ConfigFileFields input = new ConfigFileFields();
        Map<String, CatalogEntry> catalogs = new HashMap<>();
        String pinnedCommit = "0123456789abcdef0123456789abcdef01234567";
        CatalogEntry library = entry("https://git.example/library.git", "${RELEASE}");
        library.setPinnedCommit(pinnedCommit);
        catalogs.put("library", library);
        catalogs.put("custom", entry("https://git.example/custom.git", "custom-branch"));
        input.setCatalogs(catalogs);

        CatalogLauncherSettings settings = new StubCatalogSettings()
                .withCatalogUrl(jsonMapper.writeValueAsString(input))
                .withRancherServerVersion("v1.6.196");

        ConfigFileFields fields = renderConfig(settings);

        assertEquals("v1.6-release", fields.getCatalogs().get("library").getBranch());
        assertEquals(pinnedCommit, fields.getCatalogs().get("library").getPinnedCommit());
        assertEquals("custom-branch", fields.getCatalogs().get("custom").getBranch());
    }

    @Test
    public void blankVersionKeepsReleaseMarkerCompatibleWithMasterFallback() {
        ConfigFileFields fields = new ConfigFileFields();
        Map<String, CatalogEntry> catalogs = new HashMap<>();
        catalogs.put("library", entry("https://git.example/library.git", "${RELEASE}"));
        fields.setCatalogs(catalogs);

        CatalogLauncher.setReleaseBranch(fields, new StubCatalogSettings().withRancherServerVersion(""));

        assertEquals("master", fields.getCatalogs().get("library").getBranch());
    }

    @Test
    public void settingsDriveLauncherRunBinaryReloadAndStoreSelection() {
        StubCatalogSettings settings = new StubCatalogSettings()
                .withLaunchCatalog(true)
                .withCatalogExecutable("/usr/local/bin/catalog-service")
                .withDbType("mysql");
        CatalogLauncher launcher = new CatalogLauncher(settings);

        assertTrue(launcher.shouldRun());
        assertEquals("/usr/local/bin/catalog-service", launcher.binaryPath());
        assertFalse(launcher.usesSqliteCatalogStore());
        assertSame(settings.catalogUrlProperty(), launcher.getReloadSettings().get(0));
        assertSame(settings.catalogRefreshIntervalProperty(), launcher.getReloadSettings().get(1));
        assertSame(settings.rancherServerVersionProperty(), launcher.getReloadSettings().get(2));

        settings.withLaunchCatalog(false).withDbType("postgres");

        assertFalse(launcher.shouldRun());
        assertTrue(launcher.usesSqliteCatalogStore());
    }

    private ConfigFileFields renderConfig(CatalogLauncherSettings settings) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        CatalogLauncher.prepareConfigFile(baos, jsonMapper, settings);
        return jsonMapper.readValue(baos.toByteArray(), ConfigFileFields.class);
    }

    private static CatalogEntry entry(String url, String branch) {
        CatalogEntry entry = new CatalogEntry();
        entry.setUrl(url);
        entry.setBranch(branch);
        return entry;
    }

    private static class StubCatalogSettings implements CatalogLauncherSettings {

        private final StaticConfigProperty<String> catalogUrl = new StaticConfigProperty<>("library=https://git.example/library.git");
        private final StaticConfigProperty<String> catalogRefreshInterval = new StaticConfigProperty<>("300");
        private final StaticConfigProperty<String> rancherServerVersion = new StaticConfigProperty<>("v1.6.196");
        private String catalogExecutable = "/usr/bin/catalog-service";
        private boolean launchCatalog;
        private String dbType = "sqlite";
        private String dbParams;
        private String dbHost = "db.example";
        private String dbPort = "3306";
        private String dbName = "cattle";
        private String dbUser = "cattle";
        private String dbPassword = "secret";

        StubCatalogSettings withCatalogUrl(String value) {
            catalogUrl.value = value;
            return this;
        }

        StubCatalogSettings withRancherServerVersion(String value) {
            rancherServerVersion.value = value;
            return this;
        }

        StubCatalogSettings withCatalogExecutable(String value) {
            catalogExecutable = value;
            return this;
        }

        StubCatalogSettings withLaunchCatalog(boolean value) {
            launchCatalog = value;
            return this;
        }

        StubCatalogSettings withDbType(String value) {
            dbType = value;
            return this;
        }

        @Override
        public ConfigProperty<String> catalogUrlProperty() {
            return catalogUrl;
        }

        @Override
        public ConfigProperty<String> catalogRefreshIntervalProperty() {
            return catalogRefreshInterval;
        }

        @Override
        public ConfigProperty<String> rancherServerVersionProperty() {
            return rancherServerVersion;
        }

        @Override
        public String catalogExecutable() {
            return catalogExecutable;
        }

        @Override
        public boolean launchCatalog() {
            return launchCatalog;
        }

        @Override
        public String dbType() {
            return dbType;
        }

        @Override
        public String dbParams() {
            return dbParams;
        }

        @Override
        public String dbHost() {
            return dbHost;
        }

        @Override
        public String dbPort() {
            return dbPort;
        }

        @Override
        public String dbName() {
            return dbName;
        }

        @Override
        public String dbUser() {
            return dbUser;
        }

        @Override
        public String dbPassword() {
            return dbPassword;
        }
    }

    private static class StaticConfigProperty<T> implements ConfigProperty<T> {

        private T value;

        StaticConfigProperty(T value) {
            this.value = value;
        }

        @Override
        public T get() {
            return value;
        }
    }
}
