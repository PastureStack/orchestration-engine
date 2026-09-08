package io.cattle.platform.app;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import org.junit.Test;

public class ServiceRestartPolicySchemaAuthorizationTest {

    @Test
    public void projectLaunchConfigAcceptsRestartPolicyOnServiceCreateAndUpgrade() throws Exception {
        Path root = repositoryRoot();
        AuthOverlayPostProcessor processor = new AuthOverlayPostProcessor();
        processor.setJsonMapper(new JacksonJsonMapper());
        processor.setResources(Arrays.asList(
                root.resolve("resources/content/schema/user/user-auth.json").toUri().toURL(),
                root.resolve("resources/content/schema/project/project-auth.json").toUri().toURL()));
        processor.init();

        SchemaImpl launchConfig = new SchemaImpl();
        launchConfig.setId("launchConfig");
        FieldImpl restartPolicy = new FieldImpl();
        restartPolicy.setType("restartPolicy");
        launchConfig.getResourceFields().put("restartPolicy", restartPolicy);

        assertSame(launchConfig, processor.postProcessRegister(launchConfig, null));
        processor.postProcess(launchConfig, null);

        Field authorized = launchConfig.getResourceFields().get("restartPolicy");
        assertNotNull("launchConfig.restartPolicy must remain visible", authorized);
        assertTrue("service create and in-service upgrade must accept restartPolicy", authorized.isCreate());
    }

    private static Path repositoryRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("resources/content/schema/user/user-auth.json"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Unable to locate the repository root from the test working directory");
    }
}
