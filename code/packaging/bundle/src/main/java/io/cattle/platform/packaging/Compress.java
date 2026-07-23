package io.cattle.platform.packaging;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

public class Compress {

    private static String UUID = System.getProperty("uuid", java.util.UUID.randomUUID().toString());

    public static final void compress(String input, String output) throws IOException {
        FileUtils.forceMkdir(new File(output));

        FileOutputStream fis = new FileOutputStream(new File(output, "id"));
        try {
            fis.write(UUID.getBytes("UTF-8"));
        } finally {
            IOUtils.closeQuietly(fis);
        }

        File outputDir = new File(output);
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new IOException("Failed to create directory [" + outputDir.getAbsolutePath() + "]");
        }

        File resourcesFile = new File(output, "resources.jar");
        JarInputStream is = new JarInputStream(new FileInputStream(input));
        Manifest m = new Manifest(is.getManifest());
        m.getMainAttributes().putValue("X-cattle-id", UUID);

        JarOutputStream resources = new JarOutputStream(new FileOutputStream(new File(output, "resources.jar")), m);

        try {
            JarEntry entry = null;
            while ((entry = is.getNextJarEntry()) != null) {
                System.out.println("Adding [" + entry.getName() + "] to [" + resourcesFile.getPath() + "]");
                resources.putNextEntry(entry);
                IOUtils.copy(is, resources);
                resources.closeEntry();
            }
        } finally {
            IOUtils.closeQuietly(resources);
        }
    }

    public static final void main(String... args) {
        try {
            compress(args[0], args[1]);
        } catch (IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

}
