package io.cattle.platform.schema.processor;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.archaius.util.ConfigListProperty;
import io.cattle.platform.util.type.Priority;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.AbstractSchemaPostProcessor;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaPostProcessor;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class AuthSchemaAdditionsPostProcessor extends AbstractSchemaPostProcessor implements SchemaPostProcessor, Priority {

    private static final ConfigListProperty<String> AUTH_SERVICE_EXTERNAL_ID_TYPES = ArchaiusUtil.getStringListProperty("auth.service.external.id.types");

    @Override
    public SchemaImpl postProcess(SchemaImpl schema, SchemaFactory factory) {
      if(schema.getId().equals("projectMember")) {
            FieldImpl field = getField(schema, "externalIdType");
            if(field != null){
                List<String> configured = AUTH_SERVICE_EXTERNAL_ID_TYPES.get();
                LinkedHashSet<String> options = new LinkedHashSet<String>();
                if (field.getOptions() != null) {
                    options.addAll(field.getOptions());
                }
                if (configured != null) {
                    options.addAll(configured);
                }
                field.setOptions(new ArrayList<String>(options));
            }
        }
        return schema;
    }

    @Override
    public int getPriority() {
        return Priority.DEFAULT + 1;
    }

    protected FieldImpl getField(SchemaImpl schema, String name) {
        Field field = schema.getResourceFields().get(name);
        if (field instanceof FieldImpl) {
            return (FieldImpl) field;
        }
        return null;
    }
}
