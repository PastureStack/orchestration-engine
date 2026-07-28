package io.cattle.platform.api.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

import io.github.ibuildthecloud.gdapi.factory.impl.AbstractSchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.url.UrlBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FileSchemaFactoryTest {

    private ClassLoader originalClassLoader;

    @Before
    public void captureClassLoader() {
        originalClassLoader = Thread.currentThread().getContextClassLoader();
    }

    @After
    public void restoreClassLoader() {
        Thread.currentThread().setContextClassLoader(originalClassLoader);
    }

    @Test
    public void readsSerializedSchemaList() throws Exception {
        String resourceName = "schemas/test-schema.bin";
        SchemaImpl schema = schema("machine", "machines");
        Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName,
                serialize(Arrays.<Object>asList(schema))));

        FileSchemaFactory factory = factory(resourceName);

        factory.start();

        assertEquals(1, factory.listSchemas().size());
        Schema loaded = factory.listSchemas().get(0);
        assertEquals("machine", loaded.getId());
        assertSame(loaded, factory.getSchema("machine"));
        assertSame(loaded, factory.getSchema("machines"));
        assertEquals("schema", loaded.getType());
    }

    @Test(expected = ClassCastException.class)
    public void rejectsSerializedListWithNonSchemaElement() throws Exception {
        String resourceName = "schemas/invalid-schema.bin";
        Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName,
                serialize(Arrays.<Object>asList("not-a-schema"))));

        factory(resourceName).start();
    }

    private FileSchemaFactory factory(String resourceName) {
        FileSchemaFactory factory = new FileSchemaFactory();
        factory.setFile(resourceName);
        factory.setSchemaFactory(new EmptySchemaFactory());
        return factory;
    }

    private SchemaImpl schema(String id, String pluralName) {
        SchemaImpl schema = new SchemaImpl();
        schema.setId(id);
        schema.setPluralName(pluralName);
        schema.setList(false);
        Map<String, URL> links = new LinkedHashMap<String, URL>();
        links.put(UrlBuilder.SELF, null);
        schema.setLinks(links);
        return schema;
    }

    private byte[] serialize(List<Object> schemas) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(schemas);
        oos.close();
        return baos.toByteArray();
    }

    private static class ResourceClassLoader extends ClassLoader {
        private final String resourceName;
        private final byte[] resource;

        ResourceClassLoader(String resourceName, byte[] resource) {
            this.resourceName = resourceName;
            this.resource = resource;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (resourceName.equals(name)) {
                return new ByteArrayInputStream(resource);
            }
            return super.getResourceAsStream(name);
        }
    }

    private static class EmptySchemaFactory extends AbstractSchemaFactory {
        @Override
        public String getId() {
            return "empty";
        }

        @Override
        public List<Schema> listSchemas() {
            return Collections.emptyList();
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
        public Class<?> getSchemaClass(String type) {
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
    }
}
