package io.cattle.platform.util.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;

import org.junit.Test;

public class UrlUtilsTest {

    @Test
    public void createsAbsoluteHttpUrlThroughUriPath() throws Exception {
        URL url = UrlUtils.toURL("https://example.com:8443/v1/projects?marker=1a1");

        assertEquals("https", url.getProtocol());
        assertEquals("example.com", url.getHost());
        assertEquals(8443, url.getPort());
        assertEquals("/v1/projects", url.getPath());
        assertEquals("marker=1a1", url.getQuery());
    }

    @Test
    public void preservesJarUrlCreatedFromProtocolParts() throws Exception {
        URL url = UrlUtils.toURL("jar", "", "file:/tmp/cattle.war!/WEB-INF/content");

        assertEquals("jar:file:/tmp/cattle.war!/WEB-INF/content", url.toExternalForm());
    }

    @Test
    public void preservesLegacyUrlConstructorFallbackForUnescapedSpaces() throws Exception {
        URL url = UrlUtils.toURL("https://example.com/ui path/index.html");

        assertEquals("https://example.com/ui path/index.html", url.toExternalForm());
    }

    @Test
    public void legacyUrlConstructorBoundaryStaysPrivate() throws Exception {
        Method legacy = UrlUtils.class.getDeclaredMethod("legacyURLFromDeprecatedConstructor", String.class,
                String.class, String.class, String.class, Exception.class);

        assertTrue(Modifier.isPrivate(legacy.getModifiers()));
        assertTrue(Modifier.isStatic(legacy.getModifiers()));

        legacy.setAccessible(true);
        URL url = (URL) legacy.invoke(null, "https://example.com/ui path/index.html", null, null, null,
                new Exception("uri rejected"));

        assertEquals("https://example.com/ui path/index.html", url.toExternalForm());
    }
}
