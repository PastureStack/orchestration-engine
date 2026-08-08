package io.cattle.platform.packaging;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Pattern;

final class SafePaths {

    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final String ISOLATED_RUNTIME_HOME = "target/runtime-home";

    private SafePaths() {
    }

    static Path child(Path root, String relativeName) throws IOException {
        String safeName = archiveEntry(relativeName);
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path result = normalizedRoot.resolve(safeName.replace('/', File.separatorChar)).normalize();
        if (!result.startsWith(normalizedRoot)) {
            throw new IOException("Archive entry escapes its destination");
        }
        return result;
    }

    static String archiveEntry(String name) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0) {
            throw new IOException("Archive entry has an invalid name");
        }

        Path path = Paths.get(name).normalize();
        if (path.isAbsolute() || path.getNameCount() == 0 || path.startsWith("..")) {
            throw new IOException("Archive entry escapes its destination");
        }

        for (Path part : path) {
            String value = part.toString();
            if (value.isEmpty() || ".".equals(value) || "..".equals(value)) {
                throw new IOException("Archive entry has an invalid path component");
            }
        }

        return path.toString().replace(File.separatorChar, '/');
    }

    static boolean isRootResource(String name) throws IOException {
        String safeName = archiveEntry(name);
        int separator = safeName.indexOf('/');
        String first = separator < 0 ? safeName : safeName.substring(0, separator);
        return "etc".equals(first) || "extensions".equals(first);
    }

    static String version(String value) throws IOException {
        if (value == null || !SAFE_VERSION.matcher(value).matches()) {
            throw new IOException("Bundle version contains unsafe filename characters");
        }
        return value;
    }

    static Path workingDirectory() {
        return Paths.get("").toAbsolutePath().normalize();
    }

    static Path installedDataDirectory() {
        return Paths.get(File.separator, "var", "lib", "cattle").toAbsolutePath().normalize();
    }

    static Path maintainedDataDirectory() {
        return Paths.get(File.separator, "var", "lib", "pasturestack").toAbsolutePath().normalize();
    }

    static Path runtimeHome(String configured) throws IOException {
        Path legacy = installedDataDirectory();
        Path maintained = maintainedDataDirectory();
        if (configured == null || configured.isEmpty() || configured.equals(legacy.toString())) {
            return legacy;
        }
        if (configured.equals(maintained.toString())) {
            return maintained;
        }
        if (configured.equals(ISOLATED_RUNTIME_HOME)) {
            return workingDirectory().resolve(ISOLATED_RUNTIME_HOME);
        }
        throw new IOException("CATTLE_HOME must use a managed runtime directory");
    }

    static Path buildInput() throws IOException {
        Path input = buildDirectory().resolve("cattle.war");
        if (!Files.isRegularFile(input)) {
            throw new IOException("The fixed build input target/cattle.war does not exist");
        }
        return input;
    }

    static Path buildOutput() throws IOException {
        return buildDirectory().resolve("classes");
    }

    private static Path buildDirectory() throws IOException {
        try {
            if (SafePaths.class.getProtectionDomain().getCodeSource() == null) {
                throw new IOException("Cannot determine the bundle build location");
            }
            Path classes = Paths.get(SafePaths.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath().normalize();
            if (!Files.isDirectory(classes) || classes.getFileName() == null
                    || !"classes".equals(classes.getFileName().toString())) {
                throw new IOException("Compress must run from the bundle target/classes directory");
            }
            Path target = classes.getParent();
            if (target == null || target.getFileName() == null || !"target".equals(target.getFileName().toString())) {
                throw new IOException("Compress must run from a Maven target directory");
            }
            return target;
        } catch (URISyntaxException e) {
            throw new IOException("Cannot resolve the bundle build location", e);
        }
    }
}
