package io.cattle.platform.util.net;

import static org.junit.Assert.assertEquals;

import java.net.URL;

import org.junit.Test;

public class UrlUtilsLegacyProtocolPartsTest {

    @Test
    public void preservesLegacyProtocolPartsFallbackForJarUrlWithSpaces() throws Exception {
        URL url = UrlUtils.toURL("jar", "", "file:/tmp/cattle war.war!/WEB-INF/content");

        assertEquals("jar:file:/tmp/cattle war.war!/WEB-INF/content", url.toExternalForm());
    }
}
