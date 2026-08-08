package io.cattle.platform.launcher.url;

import static org.junit.Assert.assertEquals;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.Test;

public class JarInJarHandlerTest {

    @Test
    public void createsSelfContainedInJarUrlWithoutGlobalFactory() throws Exception {
        File jar = File.createTempFile("cattle-launcher-", ".jar");
        jar.deleteOnExit();

        try (JarOutputStream out = new JarOutputStream(java.nio.file.Files.newOutputStream(jar.toPath()))) {
            out.putNextEntry(new JarEntry("WEB-INF/lib/nested.jar"));
            out.write("nested-content".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }

        URL nested = JarInJarHandler.createJarInJar(jar.toURI().toURL(), "WEB-INF/lib/nested.jar");
        assertEquals(JarInJarHandlerFactory.INJAR_PROTOCOL, nested.getProtocol());

        URLConnection connection = nested.openConnection();
        assertEquals("jar", connection.getURL().getProtocol());

        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                content.write(buffer, 0, read);
            }
            assertEquals("nested-content", new String(content.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}
