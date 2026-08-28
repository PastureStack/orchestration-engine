package io.cattle.platform.packaging;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.junit.After;
import org.junit.Test;

public class SafePathsTest {

    private final List<Path> temporaryRoots = new ArrayList<Path>();

    @After
    public void cleanTemporaryRoots() throws Exception {
        for (Path root : temporaryRoots) {
            if (!Files.exists(root)) {
                continue;
            }
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                });
            }
        }
    }

    @Test
    public void resolvesArchiveEntryInsideDestination() throws Exception {
        Path root = Files.createTempDirectory(SafePaths.workingDirectory(), "safe-paths-");
        temporaryRoots.add(root);
        assertTrue(SafePaths.child(root, "etc/cattle/config.properties").startsWith(root));
        assertTrue(SafePaths.isRootResource("etc/cattle/config.properties"));
        assertTrue(SafePaths.isRootResource("extensions/example.jar"));
    }

    @Test(expected = IOException.class)
    public void rejectsParentTraversal() throws Exception {
        SafePaths.archiveEntry("../../outside");
    }

    @Test(expected = IOException.class)
    public void rejectsWindowsTraversal() throws Exception {
        SafePaths.archiveEntry("..\\outside");
    }

    @Test(expected = IOException.class)
    public void rejectsRootResourcePrefixCollision() throws Exception {
        if (!SafePaths.isRootResource("etc-malicious/payload")) {
            throw new IOException("Rejected as expected");
        }
    }

    @Test
    public void acceptsNumericMaintenanceVersion() throws Exception {
        assertEquals("0.183.286", SafePaths.version("0.183.286"));
    }

    @Test
    public void acceptsOnlyManagedRuntimeHomes() throws Exception {
        assertEquals(SafePaths.installedDataDirectory(), SafePaths.runtimeHome(null));
        assertEquals(SafePaths.maintainedDataDirectory(),
                SafePaths.runtimeHome(SafePaths.maintainedDataDirectory().toString()));
        assertEquals(SafePaths.workingDirectory().resolve("target/runtime-home"),
                SafePaths.runtimeHome("target/runtime-home"));
    }

    @Test(expected = IOException.class)
    public void rejectsArbitraryRuntimeHome() throws Exception {
        SafePaths.runtimeHome(SafePaths.workingDirectory().resolve("unmanaged").toString());
    }

    @Test(expected = IOException.class)
    public void rejectsVersionUsedAsAPath() throws Exception {
        SafePaths.version("../../payload");
    }
}
