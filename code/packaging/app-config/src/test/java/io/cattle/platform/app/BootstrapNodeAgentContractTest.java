package io.cattle.platform.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** The Linux bootstrap must start the formal Node producer, never old pyagent. */
public class BootstrapNodeAgentContractTest {
    private Path sourceRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve(
                "resources/content/config-content/bootstrap/bootstrap.sh"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Bootstrap source is unavailable");
        }
        return current;
    }

    @Test
    public void startsInstalledNodeWithExecAndParentLifecycleWithoutLegacyFallback() throws Exception {
        String source = Files.readString(sourceRoot().resolve(
                "resources/content/config-content/bootstrap/bootstrap.sh"));
        int start = source.indexOf("start_agent()\n");
        int end = source.indexOf("\nprint_config()", start);
        assertTrue(start >= 0 && end > start);
        String launcher = source.substring(start, end);
        assertTrue(launcher.contains("local main=${CATTLE_HOME}/node-agent/apply.sh"));
        assertTrue(launcher.contains("export AGENT_PARENT_PID=$PPID"));
        assertTrue(launcher.contains("exec \"$main\" start"));
        assertFalse(launcher.contains("/pyagent/"));
        assertFalse(launcher.contains("||"));
    }

    @Test
    public void preservesAuthenticatedConfigPackageAggregateAndHostBeforeNodeOrder() throws Exception {
        Path root = sourceRoot();
        String source = Files.readString(root.resolve(
                "resources/content/config-content/bootstrap/bootstrap.sh"));
        String defaults = Files.readString(root.resolve(
                "code/packaging/app-config/src/main/resources/META-INF/cattle/api-server/defaults.properties"));
        assertTrue(source.contains("CONTENT_URL=/configcontent/configscripts"));
        assertTrue(source.contains("INSTALL_ITEMS=\"configscripts pyagent\""));
        assertTrue(source.contains("bash $TEMP_DOWNLOAD/*/config.sh --force $INSTALL_ITEMS"));
        assertTrue(defaults.contains("agent.packages.pyagent=host-api,python-agent"));
    }
}
