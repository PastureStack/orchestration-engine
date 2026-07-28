package io.cattle.platform.core.addon;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CatalogTemplateTest {

    @Test
    public void prefersPlatformComposeInput() {
        CatalogTemplate template = new CatalogTemplate();
        template.setPlatformCompose("platform: true");
        template.setRancherCompose("legacy: true");

        assertEquals("platform: true", template.getPlatformCompose());
        assertEquals("platform: true", template.getRancherCompose());
    }

    @Test
    public void readsPersistedComposeInputForCompatibility() {
        CatalogTemplate template = new CatalogTemplate();
        template.setRancherCompose("legacy: true");

        assertEquals("legacy: true", template.getPlatformCompose());
        assertEquals("legacy: true", template.getRancherCompose());
    }
}
