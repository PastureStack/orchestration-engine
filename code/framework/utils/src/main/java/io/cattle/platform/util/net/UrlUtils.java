package io.cattle.platform.util.net;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

public final class UrlUtils {

    private UrlUtils() {
    }

    public static URL toURL(String spec) throws MalformedURLException {
        try {
            return new URI(spec).toURL();
        } catch (URISyntaxException | RuntimeException e) {
            return legacyURL(spec, e);
        }
    }

    public static URL toURL(String protocol, String host, String file) throws MalformedURLException {
        try {
            if (host == null || host.length() == 0) {
                return new URI(protocol + ":" + file).toURL();
            }
            return new URI(protocol, host, file, null).toURL();
        } catch (URISyntaxException | RuntimeException e) {
            return legacyURL(protocol, host, file, e);
        }
    }

    private static URL legacyURL(String spec, Exception cause) throws MalformedURLException {
        return legacyURLFromDeprecatedConstructor(spec, null, null, null, cause);
    }

    private static URL legacyURL(String protocol, String host, String file, Exception cause) throws MalformedURLException {
        return legacyURLFromDeprecatedConstructor(null, protocol, host, file, cause);
    }

    @SuppressWarnings("deprecation")
    private static URL legacyURLFromDeprecatedConstructor(String spec, String protocol, String host, String file, Exception cause) throws MalformedURLException {
        try {
            if (spec != null) {
                return new URL(spec);
            }
            return new URL(protocol, host, file);
        } catch (MalformedURLException e) {
            addSuppressed(e, cause);
            throw e;
        }
    }

    private static void addSuppressed(MalformedURLException target, Exception cause) {
        if (cause != null && cause != target) {
            target.addSuppressed(cause);
        }
    }
}
