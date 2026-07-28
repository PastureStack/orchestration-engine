package io.cattle.platform.systemstack.model;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class TemplateTest {

    @Test
    public void prefersPlatformComposeFile() {
        Template template = templateWithFiles(
                "platform-compose.yml", "platform: true",
                "rancher-compose.yml", "legacy: true");

        assertEquals("platform: true", template.getPlatformCompose());
    }

    @Test
    public void readsPersistedComposeFilenameForCompatibility() {
        Template template = templateWithFiles("rancher-compose.yml", "legacy: true");

        assertEquals("legacy: true", template.getPlatformCompose());
        assertEquals("legacy: true", template.getRancherCompose());
    }

    private Template templateWithFiles(String... entries) {
        Map<String, String> files = new HashMap<String, String>();
        for (int i = 0; i < entries.length; i += 2) {
            files.put(entries[i], entries[i + 1]);
        }

        Template template = new Template();
        template.setFiles(files);
        return template;
    }
}
