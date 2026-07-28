package io.cattle.platform.docker.machine.launch;

import static io.cattle.platform.server.context.ServerContext.*;

import io.cattle.platform.archaius.util.ConfigProperty;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.hazelcast.membership.ClusterService;
import io.cattle.platform.hazelcast.membership.ClusteredMember;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.service.launcher.GenericServiceLauncher;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Strings;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebsocketProxyLauncher extends GenericServiceLauncher {

    private static final Logger log = LoggerFactory.getLogger(WebsocketProxyLauncher.class);
    private static final String MASTER_CONF = "master.conf";
    private static final WebsocketProxyLauncherSettings DEFAULT_SETTINGS = ArchaiusWebsocketProxyLauncherSettings.create();

    @Inject
    ClusterService clusterService;

    String written = "start";
    private WebsocketProxyLauncherSettings settings;

    public WebsocketProxyLauncher() {
        this(DEFAULT_SETTINGS);
    }

    WebsocketProxyLauncher(WebsocketProxyLauncherSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void setSettings(WebsocketProxyLauncherSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    protected List<ConfigProperty<String>> getReloadSettings() {
        List<ConfigProperty<String>> list = new ArrayList<ConfigProperty<String>>();
        list.add(settings.accessLogProperty());
        list.add(settings.apiInterceptorConfigProperty());
        return list;
    }

    protected void prepareConfigFile() throws IOException {
        String config = settings.apiInterceptorConfig();
        if (StringUtils.isBlank(config)) {
            new File(settings.apiInterceptorConfigFile()).delete();
        } else {
            try(FileWriter fw = new FileWriter(settings.apiInterceptorConfigFile())) {
                IOUtils.write(settings.apiInterceptorConfig(), fw);
            }
        }
    }

    @Override
    protected boolean shouldRun() {
        return Strings.CS.equals(HOST_API_PROXY_MODE_EMBEDDED, getHostApiProxyMode());
    }

    @Override
    protected boolean isReady() {
        String host = "";
        if (!clusterService.isMaster()) {
            ClusteredMember member = clusterService.getMaster();
            if (member != null) {
                host = String.format("%s:%d", member.getAdvertiseAddress(), member.getHttpPort());
            }
        }

        if (written.equals(host)) {
            return true;
        }

        try (FileWriter fw = new FileWriter(new File(MASTER_CONF + ".tmp"))) {
            IOUtils.write(host, fw);
            written = host;
        } catch (IOException e) {
            log.error("Failed to write configuration", e);
            return false;
        }

        return new File(MASTER_CONF + ".tmp").renameTo(new File(MASTER_CONF));
    }


    @Override
    protected String binaryPath() {
        return "websocket-proxy";
    }

    @Override
    protected void setEnvironment(Map<String, String> env) {
        env.clear();
        String cattleProxyAddress = "localhost:" + getProxiedPort();

        env.put("PATH", System.getenv("PATH"));
        env.put("PROXY_LISTEN_ADDRESS", ":" + getProxyPort());
        env.put("PROXY_TLS_LISTEN_ADDRESS", ":" + getProxyPort());
        env.put("PROXY_MASTER_FILE", MASTER_CONF);
        env.put("PROXY_CATTLE_ADDRESS", cattleProxyAddress);
        env.put("PROXY_HTTPS_PROXY_PROTOCOL_PORTS", getProxyProtocolHttpsPorts());
        env.put("PROXY_API_INTERCEPTOR_CONFIG_FILE", settings.apiInterceptorConfigFile());

        String processName = ManagementFactory.getRuntimeMXBean().getName();
        if (processName != null) {
            String[] parts = processName.split("@");
            if (parts.length > 0 && StringUtils.isNotEmpty(parts[0])) {
                env.put("PROXY_PARENT_PID", parts[0]);
            }
        }

        Credential cred = getCredential();
        env.put("CATTLE_ACCESS_KEY", cred.getPublicValue());
        env.put("CATTLE_SECRET_KEY", cred.getSecretValue());
    }

    @Override
    protected void prepareProcess(ProcessBuilder pb) throws IOException {
        super.prepareProcess(pb);
        prepareConfigFile();
    }

    @Override
    public void reload() {
        if (!shouldRun()) {
            return;
        }

        try {
            prepareConfigFile();
            StringBuilder apiProxyUrl = new StringBuilder();
            apiProxyUrl.append("http://localhost:").append(getProxyPort()).append("/v1-api-interceptor/reload");
            LocalReloadRequest.post(apiProxyUrl.toString());
        } catch (IOException e) {
            log.error("Failed to reload api proxy service: {}", e.getMessage());
        }
    }

    @Override
    protected LockDefinition getLock() {
        return null;
    }

    private String getProxiedPort() {
        // To match the functionality in the Jetty Main class, need to get value this way as opposed
        // to using the dynamic settings helper.
        String port = System.getenv("CATTLE_HTTP_PROXIED_PORT");
        return port == null ? System.getProperty("cattle.http.proxied.port", "8081") : port;
    }

    private String getProxyPort() {
        // To match the functionality in the Jetty Main class, need to get value this way as opposed
        // to using the dynamic settings helper.
        String port = System.getenv("CATTLE_HTTP_PORT");
        return port == null ? System.getProperty("cattle.http.port", "8080") : port;
    }

    private String getProxyProtocolHttpsPorts() {
        String ports = System.getenv("PROXY_PROTOCOL_HTTPS_PORTS");
        return ports == null ? System.getProperty("proxy.protocol.https.ports", "443") : ports;
    }

}
