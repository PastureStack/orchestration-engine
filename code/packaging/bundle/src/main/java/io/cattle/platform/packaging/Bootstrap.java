package io.cattle.platform.packaging;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.UUID;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

public class Bootstrap implements Closeable {

    public static final String RESOURCES = "resources.jar";
    public static final String MAIN = "io.cattle.platform.launcher.Main";

    boolean war = false;
    URL[] classpath = null;
    File home = null;
    File libDir = null;
    File tempDir = null;
    JarOutputStream warOutput = null;
    String warName = null;
    boolean inEntry = false;
    String version = null;

    protected void setHomeAndEnv() throws IOException {
        String configuredHome = System.getenv("CATTLE_HOME");

        if (configuredHome == null) {
            configuredHome = System.getProperty("cattle.home");
        }

        Path homePath = SafePaths.runtimeHome(configuredHome);
        Files.createDirectories(homePath);
        this.home = homePath.toFile();
        System.setProperty("cattle.home", homePath.toString() + File.separator);

        if (System.getProperty("logback.bootstrap.level") == null) {
            System.setProperty("logback.bootstrap.level", "WARN");
        }

        String lib = System.getenv("CATTLE_LIB");

        if (lib == null) {
            lib = System.getProperty("cattle.lib");
        }

        Path libPath = homePath.resolve("lib");
        if (lib != null && !lib.equals(libPath.toString())) {
            throw new IOException("CATTLE_LIB must use the managed runtime library directory");
        }
        libDir = libPath.toFile();
        System.setProperty("cattle.lib", libPath.toString());
    }

    protected void mkdirs(File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create directory [" + dir + "]");
        }
    }

    protected JarInputStream getInput() throws IOException {
        ProtectionDomain domain = Bootstrap.class.getProtectionDomain();
        CodeSource source = domain.getCodeSource();

        return new JarInputStream(source.getLocation().openStream());
    }

    public static void closeQuietly(Closeable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (IOException ioe) {
        }
    }

    protected String getCattleId() throws IOException {
        JarInputStream is = getInput();

        try {
            JarEntry entry = null;
            while ((entry = is.getNextJarEntry()) != null) {
                if (!entry.getName().equals(RESOURCES)) {
                    continue;
                }

                JarInputStream ris = new JarInputStream(is);
                try {
                    return ris.getManifest().getMainAttributes().getValue("X-cattle-id");
                } finally {
                    closeQuietly(ris);
                }
            }
        } finally {
            closeQuietly(is);
        }

        return null;
    }

    protected void determineLibDir(JarInputStream is) throws IOException {
        String detectedVersion = getVersion(is);
        if (detectedVersion == null) {
            System.err.println("No cattle version found in jar");
            version = UUID.randomUUID().toString();
        } else {
            version = SafePaths.version(detectedVersion);
        }

        libDir = SafePaths.child(libDir.toPath(), version).toFile();
    }

    protected void extractLib(JarInputStream is) throws IOException {
        if (libDir.exists()) {
            return;
        }

        if (war) {
            System.out.println("[BOOTSTRAP] Creating " + warName);
        } else {
            System.out.println("[BOOTSTRAP] Running first time extraction");
        }
        extractFiles(is, true);
        System.out.println("\n[BOOTSTRAP] Done");

        if (tempDir != null) {
            try {
                Files.move(tempDir.toPath(), libDir.toPath(), StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempDir.toPath(), libDir.toPath());
            }
            tempDir = null;
        }
    }

    protected int count(JarInputStream is) throws IOException {
        int count = 0;
        JarEntry entry = null;

        while ((entry = is.getNextJarEntry()) != null) {
            if (!entry.isDirectory())
                count++;
        }

        return count;
    }

    protected void extractFiles(JarInputStream is, boolean first) throws IOException {
        int total = first ? count(getInput()) : 0;
        int done = 0;
        int lastPrinted = -1;
        int every = 10;

        JarEntry entry = null;
        while ((entry = is.getNextJarEntry()) != null) {
            String name = SafePaths.archiveEntry(entry.getName());

            if (name.equals(RESOURCES)) {
                extractFiles(new JarInputStream(new NoCloseInputStream(is)), false);
            } else if (name.endsWith(".pack")) {
                name = name.substring(0, name.length() - 5);
                unpackPack200(is, name);
                done++;
            } else if (!entry.isDirectory()) {
                OutputStream os = getOutputStream(name, first ? !name.endsWith(".jar") : first);
                if (os == null) {
                    continue;
                }

                try {
                    byte[] buffer = new byte[8192];

                    int count = -1;

                    while ((count = is.read(buffer)) > 0) {
                        os.write(buffer, 0, count);
                    }
                } finally {
                    closeQuietly(os);
                }
                done++;
            }

            if (first) {
                int progress = ((done * 100) / total) / every;
                if (lastPrinted != progress) {
                    System.out.print((progress * every) + "% ");
                    lastPrinted = progress;
                }
            }
        }
    }

    protected void unpackPack200(JarInputStream is, String name) throws IOException {
        throw new IOException("Pack200 entries are not supported on the JDK 25 maintenance runtime. "
                + "Rebuild cattle.jar without .pack entries: " + name);
    }

    protected OutputStream getOutputStream(String name, boolean rootResource) throws IOException {
        if (warOutput == null) {
            File root = rootResource ? home : tempDir;
            File outputFile = SafePaths.child(root.toPath(), name).toFile();

            if (rootResource) {
                if (!SafePaths.isRootResource(name)) {
                    return null;
                }

                if (outputFile.exists()) {
                    return null;
                }
            }
            mkdirs(outputFile.getParentFile());

            return new FileOutputStream(outputFile);
        } else {
            if (rootResource) {
                return null;
            }

            if (inEntry) {
                warOutput.closeEntry();
            }
            warOutput.putNextEntry(new ZipEntry(SafePaths.archiveEntry(name)));
            inEntry = true;
            return new NoCloseOutputStream(warOutput);
        }
    }

    protected void createOutput() throws IOException {
        if (war) {
            Manifest manifest = null;
            JarInputStream jis = getInput();
            JarEntry entry = null;

            while ((entry = jis.getNextJarEntry()) != null) {
                if (RESOURCES.equals(entry.getName())) {
                    JarInputStream resourceIs = new JarInputStream(jis);
                    try {
                        manifest = resourceIs.getManifest();
                        break;
                    } finally {
                        closeQuietly(resourceIs);
                    }
                }
            }

            try {
                warName = "cattle-" + SafePaths.version(version) + ".war";
                Path warPath = SafePaths.child(SafePaths.workingDirectory(), warName);
                warOutput = new JarOutputStream(new FileOutputStream(warPath.toFile()), manifest);
            } finally {
                closeQuietly(jis);
            }
        } else {
            Path parent = libDir.toPath().toAbsolutePath().normalize().getParent();
            Files.createDirectories(parent);
            tempDir = Files.createTempDirectory(parent, SafePaths.version(version) + "-").toFile();
        }
    }

    protected void determineClasspath() throws IOException {
        classpath = new URL[] { libDir.toURI().toURL() };
    }

    protected String getVersion(JarInputStream input) throws IOException {
        boolean close = false;

        try {
            if (input == null) {
                close = true;
                input = getInput();
            }

            Manifest m = input.getManifest();
            String impl = m.getMainAttributes().getValue("Implementation-Version");
            String scm = m.getMainAttributes().getValue("SCM-Revision");

            if (impl == null) {
                return "dev";
            }

            if (impl.contains("SNAPSHOT") && scm != null) {
                return impl + "-" + scm + "-" + getCattleId();
            }

            return impl;
        } finally {
            if (close) {
                closeQuietly(input);
            }
        }
    }

    public void run(String... args) throws Exception {
        for (String arg : args) {
            if (arg.contains("version")) {
                System.out.println(getVersion(null));
                System.exit(0);
            } else if (arg.equals("war")) {
                war = true;
            }
        }

        System.out.println("[BOOTSTRAP] Starting Cattle");

        if (war) {
            if (args.length == 1) {
                args = new String[0];
            } else {
                args = Arrays.copyOfRange(args, 1, args.length - 1);
            }
        }

        JarInputStream is = null;
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                closeQuietly(Bootstrap.this);
            }
        });

        try {
            setHomeAndEnv();

            is = getInput();

            determineLibDir(is);

            System.out.println("[BOOTSTRAP] CATTLE_HOME=" + this.home.getAbsolutePath());
            System.out.println("[BOOTSTRAP] CATTLE_LIB=" + this.libDir.getAbsolutePath());

            createOutput();
            extractLib(is);

            closeQuietly(is);
            closeQuietly(this);

            if (war) {
                return;
            }

            determineClasspath();

            System.out.println("[BOOTSTRAP] Launching Cattle from " + Arrays.toString(classpath));
            try (URLClassLoader cl = new URLClassLoader(classpath, Bootstrap.class.getClassLoader())) {
                Class<?> mainClass = cl.loadClass(MAIN);
                Method m = mainClass.getMethod("main", String[].class);
                m.invoke(mainClass, new Object[] { args });
            }
        } finally {
            closeQuietly(is);
        }
    }

    @Override
    public void close() throws IOException {
        if (warOutput != null) {
            closeQuietly(warOutput);
            warOutput = null;
        }

        if (tempDir != null && tempDir.exists()) {
            delete(tempDir);
            tempDir = null;
        }
    }

    protected void delete(File dir) {
        if (dir == null) {
            return;
        }

        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    delete(child);
                } else {
                    child.delete();
                }
            }
        }

        dir.delete();
    }

    public static final void main(String... args) {
        try (Bootstrap b = new Bootstrap()) {
            b.run(args);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
