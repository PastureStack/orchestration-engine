package io.cattle.platform.systemstack.catalog.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class CatalogServiceImplTest {

    @Test
    public void projectMapCopiesYamlMapWithoutGenericSuppression() {
        CatalogServiceImpl service = new CatalogServiceImpl();
        Map<Object, Object> input = new HashMap<Object, Object>();
        input.put("name", "network-services");
        input.put(Integer.valueOf(1), Boolean.TRUE);

        Map<Object, Object> result = service.projectMap(input);

        assertEquals(input, result);
    }

    @Test
    public void projectMapPreservesNullYamlDocument() {
        CatalogServiceImpl service = new CatalogServiceImpl();

        assertNull(service.projectMap(null));
    }
}
