package io.cattle.platform.docker.machine.launch;

import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.constants.ProjectConstants;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.service.launcher.GenericServiceLauncher;
import io.cattle.platform.util.type.InitializationTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;

public class CatalogLauncher extends GenericServiceLauncher implements InitializationTask {

    private static final CatalogLauncherSettings DEFAULT_SETTINGS = ArchaiusCatalogLauncherSettings.create();
    private CatalogLauncherSettings settings;

    public static class CatalogEntry {
        String url;
        String branch;
        String pinnedCommit;

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getBranch() {
            return branch;
        }

        public void setBranch(String branch) {
            this.branch = branch;
        }

        public String getPinnedCommit() {
            return pinnedCommit;
        }

        public void setPinnedCommit(String pinnedCommit) {
            this.pinnedCommit = pinnedCommit;
        }

        public CatalogEntry() {
            this.branch = "master";
        }
    }

    public static class ConfigFileFields {
        Map<String, CatalogEntry> Catalogs;

        public Map<String, CatalogEntry> getCatalogs() {
            return Catalogs;
        }

        public void setCatalogs(Map<String, CatalogEntry> catalogs) {
            Catalogs = catalogs;
        }

        public ConfigFileFields() {
        }
    }

    @Inject
    JsonMapper jsonMapper;

    public CatalogLauncher() {
        this(DEFAULT_SETTINGS);
    }

    CatalogLauncher(CatalogLauncherSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void setSettings(CatalogLauncherSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    protected boolean shouldRun() {
        return settings.launchCatalog();
    }

    @Override
    protected String binaryPath() {
        return settings.catalogExecutable();
    }

    @Override
    protected List<ConfigProperty<String>> getReloadSettings() {
        List<ConfigProperty<String>> list = new ArrayList<>();
        list.add(settings.catalogUrlProperty());
        list.add(settings.catalogRefreshIntervalProperty());
        list.add(settings.rancherServerVersionProperty());
        return list;
    }

    @Override
    protected void prepareProcess(ProcessBuilder pb) throws IOException {
        List<String> args = pb.command();
        args.add("--config");
        prepareConfigFile();
        args.add("repo.json");
        args.add("--refresh-interval");
        args.add(settings.catalogRefreshIntervalSeconds());
        if (usesSqliteCatalogStore()) {
            args.add("--sqlite");
        }
    }

    protected void prepareConfigFile() throws IOException {
        File configFile = new File("repo.json");

        try (OutputStream os = new FileOutputStream(configFile.getAbsoluteFile())) {
            prepareConfigFile(os, jsonMapper);
        }
    }

    public static void prepareConfigFile(OutputStream fos, JsonMapper jsonMapper) throws IOException {
        prepareConfigFile(fos, jsonMapper, DEFAULT_SETTINGS);
    }

    static void prepareConfigFile(OutputStream fos, JsonMapper jsonMapper, CatalogLauncherSettings settings) throws IOException {
        String catUrl = settings.catalogUrl();
        ConfigFileFields configCatalogEntries = new ConfigFileFields();
        if (catUrl.startsWith("{")) {
            configCatalogEntries = jsonMapper.readValue(catUrl, ConfigFileFields.class);
        } else {
            String[] catalogs = catUrl.split(",");
            Map<String, CatalogEntry> catalogEntryMap = new HashMap<>();
            for (String catalog : catalogs) {
                catalog = catalog.trim();
                if (StringUtils.isBlank(catalog)) {
                    continue;
                }

                CatalogEntry entry = new CatalogEntry();
                String[] splitted = catalog.split("=", 2);
                String name = "library";
                String url = catalog;
                if (splitted.length == 2) {
                    name = splitted[0].trim();
                    url = splitted[1].trim();
                }

                if (StringUtils.isBlank(name) || StringUtils.isBlank(url)) {
                    continue;
                }

                entry.setUrl(url);
                entry.setBranch("master");
                catalogEntryMap.put(name, entry);
            }
            configCatalogEntries.setCatalogs(catalogEntryMap);
        }

        setReleaseBranch(configCatalogEntries, settings);
        jsonMapper.writeValue(fos, configCatalogEntries);
    }

    protected static void setReleaseBranch(ConfigFileFields fields) {
        setReleaseBranch(fields, DEFAULT_SETTINGS);
    }

    static void setReleaseBranch(ConfigFileFields fields, CatalogLauncherSettings settings) {
        String branch = settings.rancherServerVersion();
        if (StringUtils.isBlank(branch)) {
            branch = "master";
        } else {
            String[] parts = branch.split("[.]");
            if (parts.length > 2) {
                branch = String.format("%s.%s-release", parts[0], parts[1]);
            }
        }

        for (CatalogEntry entry : fields.getCatalogs().values()) {
            if ("${RELEASE}".equals(entry.getBranch())) {
                entry.setBranch(branch);
            }
        }
    }

    @Override
    protected void setEnvironment(Map<String, String> env) {
        if (!usesSqliteCatalogStore()) {
            env.put("CATALOG_SERVICE_MYSQL_ADDRESS", String.format("%s:%s", settings.dbHost(), settings.dbPort()));
            env.put("CATALOG_SERVICE_MYSQL_DBNAME", settings.dbName());
            env.put("CATALOG_SERVICE_MYSQL_USER", settings.dbUser());
            env.put("CATALOG_SERVICE_MYSQL_PASSWORD", settings.dbPassword());
            env.put("CATALOG_SERVICE_MYSQL_PARAMS", settings.dbParams() == null ? "" : settings.dbParams());
        }
        Credential cred = getCredential();
        env.put("CATALOG_SERVICE_CATTLE_ACCESS_KEY", cred.getPublicValue());
        env.put("CATALOG_SERVICE_CATTLE_SECRET_KEY", cred.getSecretValue());
        env.put("CATALOG_SERVICE_CATTLE_URL", LocalCattleApi.url());
    }

    protected boolean usesSqliteCatalogStore() {
        return !"mysql".equalsIgnoreCase(settings.dbType());
    }

    @Override
    protected LockDefinition getLock() {
        return null;
    }

    @Override
    protected boolean isReady() {
        return LocalCattleApi.isReady();
    }

    @Override
    public void reload() {
        if (!shouldRun()) {
            return;
        }

        try {
            prepareConfigFile();
            LocalReloadRequest.post("http://localhost:8088/v1-catalog/templates?action=refresh",
                    ProjectConstants.PROJECT_HEADER, "global");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
