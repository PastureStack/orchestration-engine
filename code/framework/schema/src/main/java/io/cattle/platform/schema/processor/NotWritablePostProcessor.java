package io.cattle.platform.schema.processor;

import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.AbstractSchemaPostProcessor;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaPostProcessor;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

public class NotWritablePostProcessor extends AbstractSchemaPostProcessor implements SchemaPostProcessor {

    @Override
    public SchemaImpl postProcessRegister(SchemaImpl schema, SchemaFactory factory) {
        // Self-reported audit metadata writes no user resource; readonly actors
        // must still obtain its durable ACK before running read-only tools.
        if ("aiToolCallEvent".equals(schema.getId())) {
            schema.setCreate(true);
            schema.setUpdate(false);
            schema.setDeletable(false);
            for (Field field : schema.getResourceFields().values()) {
                if (field instanceof FieldImpl writable) writable.setUpdate(false);
            }
            return schema;
        }
        schema.setCreate(false);
        schema.setUpdate(false);
        schema.setDeletable(false);

        for (Field field : schema.getResourceFields().values()) {
            if (field instanceof FieldImpl) {
                ((FieldImpl) field).setCreate(false);
                ((FieldImpl) field).setUpdate(false);
            }
        }

        return schema;
    }

}
