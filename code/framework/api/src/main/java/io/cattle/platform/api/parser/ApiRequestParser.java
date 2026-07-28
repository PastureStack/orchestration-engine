package io.cattle.platform.api.parser;

import io.cattle.platform.server.context.ServerContext;
import io.cattle.platform.server.context.ServerContext.BaseProtocol;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.parser.DefaultApiRequestParser;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;


public class ApiRequestParser extends DefaultApiRequestParser {

    private static final ApiRequestParserSettings DEFAULT_SETTINGS = ArchaiusApiRequestParserSettings.create();

    private final ApiRequestParserSettings settings;

    public ApiRequestParser() {
        this(DEFAULT_SETTINGS);
    }

    ApiRequestParser(ApiRequestParserSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("API request parser settings are required");
        }
        this.settings = settings;
    }

    @Override
    public boolean isAllowClientOverrideHeaders() {
        return settings.allowClientOverrideHeaders();
    }

    @Override
    public boolean isHttpsPort(String host, String port) {
        if (port == null && host != null) {
            String[] parts = host.split(":", 2);
            if (parts.length > 1) {
                port = parts[1];
            } else if (ServerContext.isCustomApiHost()) {
                if (ServerContext.getHostApiBaseUrl(BaseProtocol.HTTP).startsWith("https")) {
                    port = "443";
                } else {
                    port = "80";
                }
            }
        }

        for (String p : settings.httpsPorts()) {
            if (p.equals(port)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isForwardedHostAllowed(String forwardedHost, HttpServletRequest request) {
        if (settings.trustForwardedHost()) {
            return true;
        }

        HostValue forwarded = HostValue.parse(forwardedHost);
        if (forwarded == null) {
            return false;
        }

        if (forwarded.hasSameHost(HostValue.parse(request.getHeader(HOST_HEADER)))) {
            return true;
        }
        if (forwarded.matches(HostValue.parse(settings.apiHost()))) {
            return true;
        }

        List<String> allowedHosts = settings.allowedForwardedHosts();
        if (allowedHosts != null) {
            for (String allowedHost : allowedHosts) {
                if (forwarded.matches(HostValue.parse(allowedHost))) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public boolean parse(ApiRequest apiRequest) throws IOException {
        HttpServletRequest request = apiRequest.getServletContext().getRequest();

        String path = request.getServletPath();

        String[] parts = path.split("/");
        if (parts.length > 4 && "projects".equalsIgnoreCase(parts[2]) && !"projectMembers".equalsIgnoreCase(parts[4])) {
            String projectId = parts[3];

            apiRequest.setSubContext(String.format("/%s/%s", parts[2], projectId));

            String[] newPath = ArrayUtils.addAll(new String[]{"", parts[1]}, ArrayUtils.subarray(parts, 4, Integer.MAX_VALUE));
            String servletPath = StringUtils.join(newPath, "/");
            request = new ProjectHttpServletRequest(request, projectId, servletPath);
            apiRequest.getServletContext().setRequest(request);
        }

        return super.parse(apiRequest);
    }

    private static final class HostValue {
        private final String host;
        private final Integer port;
        private final boolean wildcard;

        private HostValue(String host, Integer port, boolean wildcard) {
            this.host = host;
            this.port = port;
            this.wildcard = wildcard;
        }

        static HostValue parse(String value) {
            String normalized = firstHeaderValue(value);
            if (StringUtils.isBlank(normalized)) {
                return null;
            }

            boolean wildcard = normalized.startsWith("*.");
            if (wildcard) {
                normalized = normalized.substring(2);
            }

            URI uri;
            try {
                uri = new URI(normalized.contains("://") ? normalized : "http://" + normalized);
            } catch (URISyntaxException e) {
                return null;
            }

            String host = uri.getHost();
            if (StringUtils.isBlank(host)) {
                return null;
            }

            host = host.toLowerCase(Locale.ENGLISH);
            if (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            int port = uri.getPort();
            return new HostValue(host, port == -1 ? null : port, wildcard);
        }

        boolean matches(HostValue allowed) {
            if (allowed == null) {
                return false;
            }
            if (allowed.port != null && !allowed.port.equals(port)) {
                return false;
            }
            if (allowed.wildcard) {
                return host.endsWith("." + allowed.host) && !host.equals(allowed.host);
            }
            return host.equals(allowed.host);
        }

        boolean hasSameHost(HostValue other) {
            if (other == null) {
                return false;
            }
            if (other.wildcard) {
                return host.endsWith("." + other.host) && !host.equals(other.host);
            }
            return host.equals(other.host);
        }

        private static String firstHeaderValue(String value) {
            if (value == null) {
                return null;
            }
            String[] parts = StringUtils.split(value, ",");
            return parts.length == 0 ? null : StringUtils.trim(parts[0]);
        }
    }

}
