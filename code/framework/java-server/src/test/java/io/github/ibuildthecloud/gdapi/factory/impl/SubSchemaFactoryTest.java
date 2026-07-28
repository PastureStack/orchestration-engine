package io.github.ibuildthecloud.gdapi.factory.impl;

import static org.junit.Assert.*;

import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.testobject.TestType;

import java.util.List;

import org.junit.Test;

public class SubSchemaFactoryTest {

    @Test
    public void listSchemasReturnsInitializedLiveSchemaListWithoutRawCast() {
        SchemaFactoryImpl parent = new SchemaFactoryImpl();
        parent.getTypes().add(TestType.class);
        parent.init();

        SubSchemaFactory factory = new SubSchemaFactory();
        factory.setSchemaFactory(parent);
        factory.init();

        List<Schema> schemas = factory.listSchemas();

        assertSame(schemas, factory.listSchemas());
        assertFalse(schemas.isEmpty());
        assertNotNull(factory.getSchema(TestType.class));
    }

}
