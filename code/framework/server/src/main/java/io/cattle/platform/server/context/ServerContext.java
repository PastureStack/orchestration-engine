package io.cattle.platform.server.context;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigProperty;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;

public class ServerContext {

    public static final ConfigProperty<Integer> HTTP_PORT = ArchaiusUtil.getIntProperty("cattle.http.port");
    public static final ConfigProperty<Integer> HTTPS_PORT = ArchaiusUtil.getIntProperty("cattle.https.port");
    public static final ConfigProperty<String> URL_PATH = ArchaiusUtil.getStringProperty("cattle.url.path");
    public static final ConfigProperty<String> SERVER_IP = ArchaiusUtil.getStringProperty("cattle.server.ip");
    public static final ConfigProperty<String> SERVER_ID = ArchaiusUtil.getStringProperty("cattle.server.id");
    public static final ConfigProperty<String> HOST = ArchaiusUtil.getStringProperty("api.host");

    private static final String FOUND_SERVER_IP = lookupServerIp();
    private static final String SERVER_ID_FORMAT = System.getProperty("cattle.server.id.format", "%s");

    public static final String HOST_API_PROXY_MODE_OFF = "off";
    public static final String HOST_API_PROXY_MODE_EMBEDDED = "embedded";
    public static final String HOST_API_PROXY_MODE_HA = "ha";

    public static boolean isCustomApiHost() {
        return !isBlank(HOST.get());
    }

    public enum BaseProtocol {
        HTTP, WEBSOCKET
    }

    public static String getLocalhostUrl(BaseProtocol proto) {
        StringBuilder buffer = new StringBuilder();
        if (HTTPS_PORT.get() > 0) {
            buffer.append("https://localhost");
            buffer.append(":").append(HTTPS_PORT.get());
        } else {
            buffer.append("http://localhost");
            buffer.append(":").append(HTTP_PORT.get());
        }
        String url = buffer.toString();

        if (BaseProtocol.WEBSOCKET.equals(proto)) {
            url = url.replaceFirst("http", "ws");
        } else {
            // websocket endpoints don't follow same pathing as rest of api
            url += URL_PATH.get();
        }

        return url;
    }

    public static String getHostApiBaseUrl(BaseProtocol proto) {
        String url = null;

        if (ServerContext.isCustomApiHost()) {
            String apiHost = HOST.get();
            if (!apiHost.startsWith("http")) {
                apiHost = "http://" + apiHost;
            }
            url = apiHost;
        }

        if (url == null) {
            StringBuilder buffer = new StringBuilder();
            if (HTTPS_PORT.get() > 0) {
                buffer.append("https://");
                buffer.append(getServerIp());
                buffer.append(":").append(HTTPS_PORT.get());
            } else {
                buffer.append("http://");
                buffer.append(getServerIp());
                buffer.append(":").append(HTTP_PORT.get());
            }
            url = buffer.toString();
        }

        if (BaseProtocol.WEBSOCKET.equals(proto)) {
            url = url.replaceFirst("http", "ws");
        } else {
            // websocket endpoints don't follow same pathing as rest of api
            url += URL_PATH.get();
        }

        return url;
    }

    public static String getServerId() {
        String id = SERVER_ID.get();
        String ip = getServerIp();

        if (id != null) {
            return String.format(id, ip);
        }

        return String.format(SERVER_ID_FORMAT, ip);
    }

    public static String getHostApiProxyMode() {
        String embedded = System.getenv("CATTLE_HOST_API_PROXY_MODE");
        if (isEmpty(embedded)) {
            embedded = System.getProperty("host.api.proxy.mode", "off");
        }
        return embedded;
    }

    protected static String getServerIp() {
        String ip = SERVER_IP.get();
        return ip == null ? FOUND_SERVER_IP : ip;
    }

    protected static String lookupServerIp() {
        String address = null;
        String v6Address = null;

        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr instanceof Inet6Address) {
                        v6Address = addr.getHostAddress();
                    } else {
                        if (!addr.isLoopbackAddress() && (address == null || !addr.isSiteLocalAddress())) {
                            address = addr.getHostAddress();
                        }
                    }
                }
            }

            if (address != null) {
                return address;
            } else if (v6Address != null) {
                return v6Address;
            } else {
                return "localhost";
            }
        } catch (SocketException e) {
            throw new IllegalStateException("Failed to lookup IP of server", e);
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

}
