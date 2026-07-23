package io.cattle.platform.api.servlet;

import io.cattle.platform.metrics.util.MetricsUtil;
import io.cattle.platform.spring.web.SpringFilter;
import io.cattle.platform.util.exception.ExceptionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.servlet.ApiRequestFilterDelegate;
import io.github.ibuildthecloud.gdapi.util.RequestUtils;
import io.github.ibuildthecloud.gdapi.version.Versions;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import jakarta.inject.Inject;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.cloudstack.managed.context.ManagedContextRunnable;
import org.apache.commons.lang3.StringUtils;
import com.codahale.metrics.Timer;

public class ApiRequestFilter extends SpringFilter {

    private static final ApiRequestFilterSettings DEFAULT_SETTINGS = ArchaiusApiRequestFilterSettings.create();
    private static final String PL = "PL";
    private static final String LANG = "LANG";
    private static final String VERSION = "X-PastureStack-Version";
    private static final String LEGACY_VERSION = "X-Rancher-Version";

    private final ApiRequestFilterSettings settings;
    ApiRequestFilterDelegate delegate;
    Versions versions;
    Map<String, Timer> timers = new ConcurrentHashMap<String, Timer>();
    IndexFile indexFile;
    SecurityHeaders securityHeaders;

    public ApiRequestFilter() {
        this(DEFAULT_SETTINGS, new IndexFile(), new SecurityHeaders());
    }

    ApiRequestFilter(ApiRequestFilterSettings settings, IndexFile indexFile, SecurityHeaders securityHeaders) {
        if (settings == null) {
            throw new IllegalArgumentException("API request filter settings are required");
        }
        if (indexFile == null) {
            throw new IllegalArgumentException("Index file is required");
        }
        if (securityHeaders == null) {
            throw new IllegalArgumentException("Security headers are required");
        }
        this.settings = settings;
        this.indexFile = indexFile;
        this.securityHeaders = securityHeaders;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        super.init(filterConfig);
        indexFile.init();
    }

    @Override
    public void doFilter(final ServletRequest request, final ServletResponse response, final FilterChain chain) throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        String path = httpRequest.getServletPath();

        securityHeaders.apply(httpRequest, (HttpServletResponse) response);

        boolean ignore = isIgnoredPath(path);

        if (ignore) {
            chain.doFilter(request, response);
            return;
        }

        addPLCookie(httpRequest, (HttpServletResponse) response);
        addDefaultLanguageCookie(httpRequest, (HttpServletResponse) response);
        addVersionHeader(httpRequest, (HttpServletResponse) response);

        if (isUIRequest(httpRequest, path)) {
            if (isIndexHtmlPath(path) && indexFile.isLocal()) {
                chain.doFilter(request, response);
                return;
            }

            if ((path.contains(".") && !isIndexHtmlPath(path)) || !indexFile.canServeContent()) {
                chain.doFilter(request, response);
                return;
            } else {
                indexFile.serveIndex((HttpServletRequest)request, (HttpServletResponse) response);
                return;
            }
        }

        try {
            new ManagedContextRunnable() {
                @Override
                protected void runInContext() {
                    long start = System.currentTimeMillis();
                    boolean success = false;
                    ApiContext context = null;
                    try {
                        context = delegate.doFilter(request, response, chain);
                        success = true;
                    } catch (IOException e) {
                        throw new WrappedException(e);
                    } catch (ServletException e) {
                        throw new WrappedException(e);
                    } finally {
                        done(context, start, success);
                    }
                }
            }.run();
        } catch (WrappedException e) {
            Throwable t = e.getCause();
            ExceptionUtils.rethrow(t, IOException.class);
            ExceptionUtils.rethrow(t, ServletException.class);
            ExceptionUtils.rethrowExpectedRuntime(t);
        }
    }

    protected void addVersionHeader(HttpServletRequest httpRequest, HttpServletResponse response) {
        response.setHeader(VERSION, settings.serverVersion());
        response.setHeader(LEGACY_VERSION, settings.serverVersion());
    }

    boolean isIgnoredPath(String path) {
        for (String prefix : settings.ignorePaths()) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    protected void done(ApiContext context, long start, boolean success) {
        if (context == null) {
            return;
        }

        ApiRequest request = context.getApiRequest();
        if (request == null) {
            return;
        }

        if (request.getResponseCode() >= 400) {
            success = false;
        }

        long duration = System.currentTimeMillis() - start;
        if (request != null) {
            String key = String.format("api.%s.%s.%s", success ? "success" : "failed", request.getType(), request.getMethod().toLowerCase());

            Timer timer = timers.get(key);
            if (timer == null) {
                timer = MetricsUtil.getRegistry().timer(key);
                timers.put(key, timer);
            }
            timer.update(duration, TimeUnit.MILLISECONDS);
        }
    }

    protected boolean isUIRequest(HttpServletRequest request, String path) {
        path = path.replaceAll("//+", "/");

        if ("/".equals(path)) {
            return RequestUtils.isBrowser(request, false);
        }

        boolean found = false;
        for (String version : versions.getVersions()) {
            if (path.startsWith("/" + version)) {
                found = true;
                break;
            }
        }

        return !found;
    }

    protected boolean isIndexHtmlPath(String path) {
        path = path.replaceAll("//+", "/");
        return "/index.html".equals(path);
    }

    public ApiRequestFilterDelegate getDelegate() {
        return delegate;
    }

    @Inject
    public void setDelegate(ApiRequestFilterDelegate delegate) {
        this.delegate = delegate;
    }

    public Versions getVersions() {
        return versions;
    }

    @Inject
    public void setVersions(Versions versions) {
        this.versions = versions;
    }

    private static final class WrappedException extends RuntimeException {
        private static final long serialVersionUID = 8188803805854482331L;

        public WrappedException(Throwable cause) {
            super(cause);
        }
    }


    void addPLCookie(HttpServletRequest httpRequest, HttpServletResponse response) {
        Cookie plCookie = null;
        if (httpRequest.getCookies() != null) {
            for (Cookie c : httpRequest.getCookies()) {
                if (PL.equals(c.getName()) && c.getName() != null) {
                    plCookie = c;
                    break;
                }
            }
        }

        if (plCookie == null || !settings.projectLabel().equalsIgnoreCase(plCookie.getValue())) {
            String plValue = settings.projectLabel();
            plValue = URLEncoder.encode(settings.projectLabel(), StandardCharsets.UTF_8);
            plCookie = new Cookie(PL, plValue);
            plCookie.setPath("/");
            response.addCookie(plCookie);
        }
    }

    void addDefaultLanguageCookie(HttpServletRequest httpRequest, HttpServletResponse response) {
        Cookie languageCookie = null;
        if(!StringUtils.isNotBlank(settings.localization()))
            return;
        if(httpRequest.getCookies()!=null) {
            for(Cookie c : httpRequest.getCookies()) {
                if(LANG.equals(c.getName()) && c.getName()!=null) {
                    languageCookie = c;
                    break;
                    }
                }
        }
        if(languageCookie == null) {
            languageCookie = new Cookie(LANG, settings.localization());
            languageCookie.setPath("/");
            response.addCookie(languageCookie);
        }
    }
}
