package io.cattle.platform.packaging;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.commons.io.IOUtils;
import org.junit.After;
import org.junit.Test;

public class CompressTest {

    private final List<Path> generatedFiles = new ArrayList<Path>();

    @After
    public void cleanGeneratedFiles() throws Exception {
        for (Path file : generatedFiles) {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void copiesSafeEntriesIntoResourcesJar() throws Exception {
        Path root = fixedBuildRoot();
        Path source = root.resolve("cattle.war");
        byte[] content = "safe".getBytes(StandardCharsets.UTF_8);
        writeJar(source, "etc/example.txt", content);

        Path output = root.resolve("classes");
        Compress.compress();

        assertTrue(Files.isRegularFile(output.resolve("id")));
        try (InputStream file = Files.newInputStream(output.resolve("resources.jar"));
                JarInputStream jar = new JarInputStream(file)) {
            JarEntry entry = jar.getNextJarEntry();
            assertTrue(entry.getName().equals("etc/example.txt"));
            assertArrayEquals(content, IOUtils.toByteArray(jar));
        }
    }

    @Test(expected = IOException.class)
    public void rejectsMaliciousArchiveEntry() throws Exception {
        Path root = fixedBuildRoot();
        Path source = root.resolve("cattle.war");
        writeJar(source, "../outside", "bad".getBytes(StandardCharsets.UTF_8));
        Compress.compress();
    }

    @Test
    public void buildPathsAreFixedInsideTarget() throws Exception {
        Path root = fixedBuildRoot();
        writeJar(root.resolve("cattle.war"), "safe", new byte[] { 1 });
        assertTrue(SafePaths.buildInput().equals(root.resolve("cattle.war")));
        assertTrue(SafePaths.buildOutput().equals(root.resolve("classes")));
    }

    private static void writeJar(Path target, String entryName, byte[] content) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target), manifest)) {
            jar.putNextEntry(new JarEntry(entryName));
            jar.write(content);
            jar.closeEntry();
        }
    }

    private Path fixedBuildRoot() throws IOException {
        Path root = SafePaths.buildOutput().getParent();
        Files.createDirectories(root);
        generatedFiles.add(root.resolve("classes/resources.jar"));
        generatedFiles.add(root.resolve("classes/id"));
        generatedFiles.add(root.resolve("cattle.war"));
        return root;
    }
}
