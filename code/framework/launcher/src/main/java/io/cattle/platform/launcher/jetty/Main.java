package io.cattle.platform.launcher.jetty;

import static io.cattle.platform.server.context.ServerContext.*;

import io.cattle.platform.util.net.UrlUtils;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

import org.apache.commons.lang3.Strings;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.ee10.plus.webapp.EnvConfiguration;
import org.eclipse.jetty.ee10.plus.webapp.PlusConfiguration;
import org.eclipse.jetty.ee10.webapp.Configuration;
import org.eclipse.jetty.ee10.webapp.FragmentConfiguration;
import org.eclipse.jetty.ee10.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.ee10.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebXmlConfiguration;
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {

    public static final String WEB_XML = "WEB-INF/web.xml";
    public static final String OVERRIDE_WEB_XML = "WEB-INF/override-web.xml";
    public static final String STATIC_WEB_XML = "WEB-INF/static-override-web.xml";
    public static final String DEFAULT_WEB_XML = "WEB-INF/default-web.xml";

    // private static final Logger log = LoggerFactory.getLogger(Main.class);
    private static final Logger CONSOLE_LOG = LoggerFactory.getLogger("ConsoleStatus");

    public static final String[] PREFIXES = new String[] { "code/packaging/app/src/main/webapp/", "src/main/webapp/", "" };

    protected static URL findUrl(String suffix) throws IOException {
        File file = findFile(suffix);
        if (file != null) {
            return file.toURI().toURL();
        }

        return Main.class.getResource("/" + suffix);
    }

    protected static File findFile(String suffix) {
        for (String prefix : PREFIXES) {
            File file = new File(prefix + suffix);

            if (file.exists())
                return new File(file.getAbsolutePath());
        }

        URL url = Main.class.getResource("/" + suffix);
        if (url != null && "file".equals(url.getProtocol())) {
            return new File(url.getPath());
        }

        return null;
    }

    protected static URL getContextRoot(URL webXml) throws IOException {
        if (webXml != null) {
            URLConnection connection = webXml.openConnection();
            if (connection instanceof JarURLConnection) {
                URL war = ((JarURLConnection) connection).getJarFileURL();
                return UrlUtils.toURL("jar", "", war.toExternalForm() + "!/WEB-INF/content");
            }
        }
        return Main.class.getResource("");
    }

    protected static String getHttpPort() {
        boolean proxyEmbedded = Strings.CS.equals(HOST_API_PROXY_MODE_EMBEDDED, getHostApiProxyMode());
        if(proxyEmbedded) {
            String port = System.getenv("CATTLE_HTTP_PROXIED_PORT");
            return port == null ? System.getProperty("cattle.http.proxied.port", "8081") : port;
        } else {
            String port = System.getenv("CATTLE_HTTP_PORT");
            return port == null ? System.getProperty("cattle.http.port", "8080") : port;
        }
    }

    public static void main(String... args) {
        /*
         * The world is better place without time zones. Well, at least for
         * computers
         */
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        long start = System.currentTimeMillis();

        try {
            Server s = new Server();

            HttpConfiguration httpConfig = new HttpConfiguration();
            httpConfig.setRequestHeaderSize(16 * 1024);
            httpConfig.setOutputBufferSize(512);
            httpConfig.setSendServerVersion(false);
            ServerConnector http = new ServerConnector(s, new HttpConnectionFactory(httpConfig));
            http.setPort(Integer.parseInt(getHttpPort()));
            s.setConnectors(new Connector[] {http});
            s.setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", -1);

            MBeanContainer mbContainer = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());
            s.addEventListener(mbContainer);
            s.addBean(mbContainer);

            WebAppContext context = new WebAppContext();
            context.setThrowUnavailableOnStartupException(true);
            context.setConfigurations(new Configuration[] {
                    new WebInfConfiguration(),
                    new WebXmlConfiguration(),
                    new MetaInfConfiguration(),
                    new FragmentConfiguration(),
                    new EnvConfiguration(),
                    new PlusConfiguration(),
                    new JettyWebXmlConfiguration()
            });

            File webXmlFile = findFile(WEB_XML);

            URL webXml = findUrl(WEB_XML);
            URL contextRoot = webXmlFile == null ? getContextRoot(webXml) : webXmlFile.getParentFile().getParentFile().toURI().toURL();

            URL override = findUrl(OVERRIDE_WEB_XML);
            if (override != null) {
                context.setOverrideDescriptors(Arrays.asList(override.toExternalForm()));
            }

            URL defaultWebXml = findUrl(DEFAULT_WEB_XML);
            if (defaultWebXml != null) {
                context.setDefaultsDescriptor(defaultWebXml.toExternalForm());
            }

            URL staticOverideXml = findUrl(STATIC_WEB_XML);
            if (staticOverideXml != null && new File("./content").exists()) {
                List<String> overrides = new ArrayList<String>(context.getOverrideDescriptors());
                overrides.add(staticOverideXml.toExternalForm());
                context.setOverrideDescriptors(overrides);
            }

            if (contextRoot != null) {
                context.setWar(contextRoot.toExternalForm());
            }

            context.setParentLoaderPriority(true);
            // Rancher embeds Jetty in-process. Keep Jetty and servlet API classes unified
            // in the parent loader so WEB-INF dependencies cannot create duplicate Jetty
            // class identities under JDK 25 / Jetty 12.
            context.getHiddenClassMatcher().add("-org.eclipse.jetty.", "-jakarta.servlet.", "-jakarta.websocket.");
            context.getProtectedClassMatcher().add("org.eclipse.jetty.", "jakarta.servlet.", "jakarta.websocket.");
            context.setClassLoader(new WebAppClassLoader(Main.class.getClassLoader(), context));
            context.setContextPath("/");
            JettyWebSocketServletContainerInitializer.configure(context, null);

            s.setHandler(context);
            s.start();

            CONSOLE_LOG.info("[DONE ] [{}ms] Startup succeeded", (System.currentTimeMillis() - start));

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                if ("--exit".equals(arg)) {
                    System.exit(0);
                }

                if ("--notify".equals(arg)) {
                    String requestedCommand = notificationArgument(args, i);
                    CONSOLE_LOG.info("[POST ] [{}ms] Calling startup notification", (System.currentTimeMillis() - start));
                    runNotifyCommand(Notification.SUCCESS, requestedCommand);
                }

            }

            s.join();
        } catch (Exception e) {
            e.printStackTrace();
            CONSOLE_LOG.error("Startup Failed [{}ms]", (System.currentTimeMillis() - start), e);
            System.err.println("STARTUP FAILED [" + (System.currentTimeMillis() - start) + "] ms");

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];

                if ("--notify-error".equals(arg)) {
                    String requestedCommand = notificationArgument(args, i);
                    CONSOLE_LOG.error("[ERROR] [{}ms] Calling startup failure notification", (System.currentTimeMillis() - start));
                    try {
                        runNotifyCommand(Notification.ERROR, requestedCommand);
                    } catch (Exception e1) {
                        e1.printStackTrace();
                    }
                }
            }

            System.exit(1);
        }
    }

    enum Notification {
        SUCCESS("../../../tools/development/run-success.sh"),
        ERROR("../../../tools/development/run-error.sh");

        private final String command;

        Notification(String command) {
            this.command = command;
        }
    }

    static int runNotifyCommand(Notification notification, String requestedCommand)
            throws IOException, InterruptedException {
        return new ProcessBuilder(notificationCommand(notification, requestedCommand)).start().waitFor();
    }

    static List<String> notificationCommand(Notification notification, String requestedCommand) {
        if (notification == null) {
            throw new IllegalArgumentException("Notification type is required");
        }
        if (!notification.command.equals(requestedCommand)) {
            throw new IllegalArgumentException("Unsupported notification command");
        }
        return Collections.singletonList(notification.command);
    }

    static String notificationArgument(String[] args, int optionIndex) {
        if (args == null || optionIndex < 0 || optionIndex + 1 >= args.length) {
            throw new IllegalArgumentException("Notification command is missing");
        }
        return args[optionIndex + 1];
    }
}
