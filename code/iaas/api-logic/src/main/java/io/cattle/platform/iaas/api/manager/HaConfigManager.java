package io.cattle.platform.iaas.api.manager;

import io.cattle.platform.core.util.SettingsUtils;
import io.cattle.platform.framework.encryption.impl.Aes256Encrypter;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.impl.AbstractNoOpResourceManager;
import io.github.ibuildthecloud.gdapi.url.UrlBuilder;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.ProcessBuilder.Redirect;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.Key;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

public class HaConfigManager extends AbstractNoOpResourceManager {

    private static final HaConfigSettings DEFAULT_SETTINGS = ArchaiusHaConfigSettings.create();
    private static final String MYSQL_CLIENT = "/usr/bin/mysql";
    private static final String MYSQL_DUMP = "/usr/bin/mysqldump";
    private static final String POSTGRES_CLIENT = "/usr/bin/psql";
    private static final String POSTGRES_DUMP = "/usr/bin/pg_dump";

    @Inject
    Aes256Encrypter encrypter;
    @Inject
    SettingsUtils settingsUtils;
    Configuration configuration;
    Template template;
    HaConfigSettings settings;

    public HaConfigManager() {
        this(DEFAULT_SETTINGS);
    }

    HaConfigManager(HaConfigSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void setSettings(HaConfigSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    protected Object listInternal(SchemaFactory schemaFactory, String type, Map<Object, Object> criteria, ListOptions options) {
        return Arrays.asList(getHaConfig());
    }

    protected Resource getHaConfig() {
        Map<String, Object> data = new HashMap<>();
        String host = settings.dbHost();
        data.put("dbHost", host);
        data.put("enabled", settings.haEnabled());
        data.put("clusterSize", settings.haClusterSize());

        if ("localhost".equals(host) || "127.0.0.1".equals(host)) {
            try {
                data.put("dbSize", dbSize());
            } catch (NumberFormatException | IOException e) {
                data.put("dbSize", -1);
            }
        }

        ResourceImpl resource = new ResourceImpl("haConfig", "haConfig", data);
        UrlBuilder builder = ApiContext.getUrlBuilder();
        resource.getLinks().put("dbdump", builder.resourceLink(resource, "dbdump"));
        resource.getActions().put("createscript", builder.actionLink(resource, "createscript"));
        return resource;
    }

    @Override
    protected Object getLinkInternal(String type, String id, String link, ApiRequest request) {
        if ("dbdump".equalsIgnoreCase(link)) {
            try {
                return dbDump(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw dumpFailed();
            } catch (IOException | RuntimeException e) {
                // Neither CLI stderr nor exception text may expose SQL or credentials.
                throw dumpFailed();
            }
        }
        return null;
    }

    @Override
    protected Object resourceActionInternal(Object obj, ApiRequest request) {
        if ("createscript".equalsIgnoreCase(request.getAction())) {
            try {
                return getScript(request);
            } catch (IOException | TemplateException e) {
                throw new ClientVisibleException(ResponseCodes.INTERNAL_SERVER_ERROR, "ScriptFailed", "Failed to create script", e.getMessage());
            }
        }
        return null;
    }

    @Override
    protected Object updateInternal(String type, String id, Object obj, ApiRequest request) {
        Map<Object, Object> data = new HashMap<>(CollectionUtils.toMap(request.getRequestObject()));
        Boolean enabled = DataAccessor.fromMap(data).withKey("enabled").as(Boolean.class);
        if (enabled != null) {
            settingsUtils.changeSetting("ha.enabled", enabled.toString());
        }
        return getHaConfig();
    }

    protected Object getScript(ApiRequest request) throws IOException, TemplateException {
        Map<Object, Object> data = new HashMap<>(CollectionUtils.toMap(request.getRequestObject()));
        Key key = encrypter.generateKey();

        Long clusterSize = DataAccessor.fromMap(data).withKey("clusterSize")
                .withDefault(3L).as(Long.class);
        settingsUtils.changeSetting("ha.cluster.size", clusterSize.toString());

        data.put("encryptionKey", Base64.encodeBase64String(key.getEncoded()));
        data.put("db", settings.dbType());
        data.put("dbHost", settings.dbHost());
        data.put("dbPort", settings.dbPort());
        data.put("dbUser", settings.dbUser());
        data.put("dbPass", encrypter.encrypt(settings.dbPassword(), key));
        data.put("dbName", settings.dbName());
        data.put("containerPrefix", "pasturestack-ha-");

        HttpServletResponse response = request.getServletContext().getResponse();

        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=pasturestack-ha.sh");
        response.setHeader("Cache-Control", "private");
        response.setHeader("Pragma", "private");
        response.setHeader("Expires", "Sun 26 Jul 1981 18:42:00 GMT");

        URL url = Thread.currentThread().getContextClassLoader().getResource("haconfig/script.ftl");
        Template template = configuration.getTemplate(url.toExternalForm());

        try (OutputStreamWriter writer = new OutputStreamWriter(request.getOutputStream())) {
            template.process(data, writer);
        }

        return new Object();
    }

    protected long dbSize() throws IOException  {
        try {
            return withDatabaseOutput(dbSizeProcessBuilder(), 30, file -> {
                if (Files.size(file) > 128) throw new IOException("Invalid database size");
                String value = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!value.matches("[0-9]+(?:[.][0-9]+)?")) throw new IOException("Invalid database size");
                return Long.parseLong(value.split("[.]")[0]);
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Database size interrupted");
        } catch (IOException | RuntimeException e) {
            throw new IOException("Database size unavailable");
        }
    }

    protected ProcessBuilder dbSizeProcessBuilder() throws IOException {
        return "mysql".equals(settings.dbType())
                ? mysqlProcessBuilder(MYSQL_CLIENT, "--skip-column-names", "-s", "--database=" + mysqlSetting(settings.dbName()), "-e",
                "SELECT SUM(data_length)/power(1024,2) AS dbsize_mb FROM information_schema.tables WHERE table_schema=DATABASE() GROUP BY table_schema;")
                : new ProcessBuilder(POSTGRES_CLIENT, "cattle", "cattle", "-t", "-q", "-c",
                "SELECT pg_database_size('cattle')/power(1024,2)");
    }

    protected Object dbDump(ApiRequest request) throws IOException, InterruptedException {
        try {
            return withDatabaseOutput(dbDumpProcessBuilder(), 300, file -> {
                // Do not commit a success response until the CLI has exited
                // successfully and produced a nonempty private spool file.
                DateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
                df.setTimeZone(TimeZone.getTimeZone("UTC"));
                String prefix = "pasturestack-db-dump-" + df.format(new Date());
                HttpServletResponse response = request.getServletContext().getResponse();
                response.setContentType("application/octet-stream");
                response.setHeader("Content-Disposition", "attachment; filename=" + prefix + ".zip");
                response.setHeader("Cache-Control", "private");
                response.setHeader("Pragma", "private");
                response.setHeader("Expires", "Sun 26 Jul 1981 18:42:00 GMT");
                try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS);
                        ZipOutputStream out = new ZipOutputStream(response.getOutputStream())) {
                    out.putNextEntry(new ZipEntry(prefix + ".sql"));
                    IOUtils.copyLarge(in, out);
                    out.closeEntry();
                }
                return new Object();
            });
        } catch (IOException | RuntimeException e) {
            throw new IOException("Database dump unavailable");
        }
    }

    protected ProcessBuilder dbDumpProcessBuilder() throws IOException {
        return "mysql".equals(settings.dbType())
                ? mysqlProcessBuilder(MYSQL_DUMP, "--", mysqlSetting(settings.dbName()))
                : new ProcessBuilder(POSTGRES_DUMP, "-Fc", "-Ucattle", "cattle");
    }

    private ProcessBuilder mysqlProcessBuilder(String executable, String... arguments) throws IOException {
        String port = mysqlSetting(settings.dbPort());
        if (!port.matches("[0-9]{1,5}") || Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535)
            throw new IOException("Invalid database connection settings");
        String password = settings.dbPassword();
        if (password == null || password.indexOf('\0') >= 0)
            throw new IOException("Invalid database connection settings");
        var command = new ArrayList<String>();
        command.add(executable);
        command.add("--protocol=TCP");
        command.add("--host=" + mysqlSetting(settings.dbHost()));
        command.add("--port=" + port);
        command.add("--user=" + mysqlSetting(settings.dbUser()));
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command);
        // Only the child receives this value; never put a password in argv or logs.
        builder.environment().put("MYSQL_PWD", password);
        return builder;
    }

    private String mysqlSetting(String value) throws IOException {
        if (value == null || value.isBlank() || value.indexOf('\0') >= 0)
            throw new IOException("Invalid database connection settings");
        return value;
    }

    private static ClientVisibleException dumpFailed() {
        return new ClientVisibleException(ResponseCodes.INTERNAL_SERVER_ERROR, "DumpFailed",
                "Failed to create database dump", null);
    }

    @FunctionalInterface
    private interface DatabaseOutput<T> {
        T read(Path file) throws IOException;
    }

    private <T> T withDatabaseOutput(ProcessBuilder builder, long timeoutSeconds, DatabaseOutput<T> output)
            throws IOException, InterruptedException {
        Path file = null;
        Process process = null;
        try {
            file = createDatabaseTempFile();
            builder.redirectOutput(file.toFile());
            builder.redirectError(Redirect.DISCARD);
            process = startDatabaseProcess(builder);
            builder.environment().remove("MYSQL_PWD");
            process.getOutputStream().close();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS) || process.exitValue() != 0)
                throw new IOException("Database command failed");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) == 0)
                throw new IOException("Empty database output");
            return output.read(file);
        } finally {
            builder.environment().remove("MYSQL_PWD");
            try {
                stopDatabaseProcess(process);
            } finally {
                if (file != null) {
                    try {
                        Files.deleteIfExists(file);
                    } finally {
                        Files.deleteIfExists(file.getParent());
                    }
                }
            }
        }
    }

    protected Path createDatabaseTempFile() throws IOException {
        Path directory = null;
        try {
            // Permissions are applied atomically at creation, before any SQL is written.
            directory = Files.createTempDirectory("pasturestack-ha-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            return Files.createFile(directory.resolve("output"),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (IOException | UnsupportedOperationException e) {
            if (directory != null) Files.deleteIfExists(directory);
            throw new IOException("Private database temporary storage unavailable");
        }
    }

    protected Process startDatabaseProcess(ProcessBuilder builder) throws IOException {
        return builder.start();
    }

    private void stopDatabaseProcess(Process process) throws IOException {
        if (process == null) return;
        try {
            if (process.isAlive()) {
                process.destroy();
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                        if (!process.waitFor(2, TimeUnit.SECONDS)) throw new IOException("Database process did not stop");
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            IOUtils.closeQuietly(process.getInputStream());
            IOUtils.closeQuietly(process.getErrorStream());
            IOUtils.closeQuietly(process.getOutputStream());
        }
    }

    @PostConstruct
    public void init() throws IOException {
        URL url = Thread.currentThread().getContextClassLoader().getResource("haconfig/script.ftl");
        template = configuration.getTemplate(url.toExternalForm());
    }

    @Override
    public String[] getTypes() {
        return new String[] {"haConfig"};
    }

    @Override
    public Class<?>[] getTypeClasses() {
        return new Class<?>[0];
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public void setConfiguration(Configuration configuration) {
        this.configuration = configuration;
    }

}
