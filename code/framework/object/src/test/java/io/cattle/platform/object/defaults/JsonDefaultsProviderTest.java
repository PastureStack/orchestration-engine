package io.cattle.platform.object.defaults;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

public class JsonDefaultsProviderTest {

    @Test
    public void loadsDefaultsThroughTypedMapper() {
        JsonDefaultsProvider provider = providerWithJson("{\"imageUuid\":\"docker:nginx\",\"startOnCreate\":true}");
        provider.start();

        assertEquals("docker:nginx", provider.defaults.get(SampleType.class).get("imageUuid"));
        assertEquals(Boolean.TRUE, provider.defaults.get(SampleType.class).get("startOnCreate"));
    }

    @Test
    public void mergesDefaultsForRepeatedSchemaClass() {
        JsonDefaultsProvider provider = providerWithJson("{\"imageUuid\":\"docker:nginx\"}", "{\"count\":2}");
        provider.start();

        assertSame(provider.defaults.get(SampleType.class), provider.defaults.get(SampleType.class));
        assertEquals("docker:nginx", provider.defaults.get(SampleType.class).get("imageUuid"));
        assertEquals(2, provider.defaults.get(SampleType.class).get("count"));
    }

    private JsonDefaultsProvider providerWithJson(final String... jsonValues) {
        JsonDefaultsProvider provider = new JsonDefaultsProvider() {
            int index;

            @Override
            protected InputStream jsonFile(String prefix, Schema schema) {
                if (!"defaults".equals(prefix)) {
                    return null;
                }
                return new ByteArrayInputStream(jsonValues[index++].getBytes(StandardCharsets.UTF_8));
            }
        };
        provider.setDefaultPath("defaults");
        provider.setDefaultOverridePath("overrides");
        provider.setJsonMapper(new JacksonJsonMapper());
        provider.setSchemaFactory(schemaFactory(jsonValues.length));
        return provider;
    }

    private SchemaFactory schemaFactory(int schemaCount) {
        final SchemaImpl schema = new SchemaImpl();
        schema.setId("sampleType");
        return new SchemaFactory() {
            @Override
            public String getId() {
                return "test";
            }

            @Override
            public List<Schema> listSchemas() {
                return Collections.nCopies(schemaCount, schema);
            }

            @Override
            public String getSchemaName(Class<?> clz) {
                return null;
            }

            @Override
            public String getSchemaName(String type) {
                return null;
            }

            @Override
            public Schema getSchema(Class<?> clz) {
                return null;
            }

            @Override
            public Schema getSchema(String type) {
                return null;
            }

            @Override
            public Class<?> getSchemaClass(String type, boolean resolveParent) {
                return getSchemaClass(type);
            }

            @Override
            public Class<?> getSchemaClass(String type) {
                return "sampleType".equals(type) ? SampleType.class : null;
            }

            @Override
            public Class<?> getSchemaClass(Class<?> type) {
                return type;
            }

            @Override
            public String getPluralName(String type) {
                return null;
            }

            @Override
            public String getSingularName(String type) {
                return null;
            }

            @Override
            public String getBaseType(String type) {
                return null;
            }

            @Override
            public Schema registerSchema(Object obj) {
                return null;
            }

            @Override
            public Schema parseSchema(String name) {
                return null;
            }

            @Override
            public List<String> getSchemaNames(Class<?> clz) {
                return Collections.emptyList();
            }

            @Override
            public boolean typeStringMatches(Class<?> clz, String type) {
                return false;
            }
        };
    }

    private static class SampleType {
    }
}
