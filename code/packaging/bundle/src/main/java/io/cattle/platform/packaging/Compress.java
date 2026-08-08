package io.cattle.platform.packaging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.commons.io.IOUtils;

public class Compress {

    private static String UUID = System.getProperty("uuid", java.util.UUID.randomUUID().toString());

    public static final void compress() throws IOException {
        Path inputPath = SafePaths.buildInput();
        Path outputPath = SafePaths.buildOutput();
        Files.createDirectories(outputPath);

        Path idFile = SafePaths.child(outputPath, "id");
        Files.write(idFile, UUID.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);

        Path resourcesPath = SafePaths.child(outputPath, "resources.jar");
        File resourcesFile = resourcesPath.toFile();
        JarInputStream is = new JarInputStream(new FileInputStream(inputPath.toFile()));
        Manifest sourceManifest = is.getManifest();
        Manifest m = sourceManifest == null ? new Manifest() : new Manifest(sourceManifest);
        m.getMainAttributes().putValue("X-cattle-id", UUID);

        JarOutputStream resources = new JarOutputStream(new FileOutputStream(resourcesFile), m);

        try {
            JarEntry entry = null;
            while ((entry = is.getNextJarEntry()) != null) {
                String safeName = SafePaths.archiveEntry(entry.getName());
                System.out.println("Adding [" + safeName + "] to [" + resourcesFile.getPath() + "]");
                JarEntry outputEntry = new JarEntry(safeName);
                outputEntry.setTime(entry.getTime());
                resources.putNextEntry(outputEntry);
                IOUtils.copy(is, resources);
                resources.closeEntry();
            }
        } finally {
            IOUtils.closeQuietly(resources);
            IOUtils.closeQuietly(is);
        }
    }

    public static final void main(String... args) {
        try {
            if (args.length != 0) {
                throw new IOException("Compress uses the fixed target/cattle.war and target/classes build paths");
            }
            compress();
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

}
