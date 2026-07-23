package io.cattle.platform.launcher.url;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

public class JarInJarHandler extends URLStreamHandler {

    @Override
    protected URLConnection openConnection(URL u) throws IOException {
        String x = u.getPath();
        x = x.replaceAll("___", ":");
        x = x.replaceAll("__", "!");
        return toURL(x).openConnection();
    }

    public static URL createJarInJar(URL jarUrl, String location) throws MalformedURLException {
        String preUrl = "jar:" + jarUrl.toExternalForm() + "!/" + location;
        preUrl = preUrl.replaceAll("!", "__");
        preUrl = preUrl.replaceAll(":", "___");

        return toURL(JarInJarHandlerFactory.INJAR_PROTOCOL + ":" + preUrl);
    }

    private static URL toURL(String spec) throws MalformedURLException {
        /*
         * Launcher classes run before WEB-INF/lib is on the classpath. Keep this
         * boot-only URL helper self-contained instead of depending on
         * cattle-framework-utils/UrlUtils.
         */
        try {
            URI uri = new URI(spec);
            URLStreamHandler handler = JarInJarHandlerFactory.INJAR_PROTOCOL.equals(uri.getScheme()) ? new JarInJarHandler() : null;
            return URL.of(uri, handler);
        } catch (URISyntaxException | IllegalArgumentException e) {
            MalformedURLException malformed = new MalformedURLException(e.getMessage());
            malformed.initCause(e);
            throw malformed;
        }
    }

}
