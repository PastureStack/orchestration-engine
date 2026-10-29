package io.cattle.platform.schema.processor;

import static org.junit.Assert.*;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import org.junit.Test;

public class AiToolCallReadonlySchemaTest {
    @Test public void onlyOwnAuditMetadataCanBeCreatedByReadonlyRole() {
        NotWritablePostProcessor readonly = new NotWritablePostProcessor();
        for (String type : new String[] { "aiToolCallEvent", "aiResourceView", "aiResourceChangePlan", "aiServiceUpgradePlan",
                "aiServiceDescriptionUpdate", "service", "credential", "aiToolCallEvents" }) {
            SchemaImpl schema = new SchemaImpl(); schema.setId(type); schema.setCreate(true); schema.setUpdate(true); schema.setDeletable(true);
            FieldImpl field = new FieldImpl(); field.setCreate(true); field.setUpdate(true); schema.getResourceFields().put("command", field);
            readonly.postProcessRegister(schema, null);
            boolean metadataRead = "aiToolCallEvent".equals(type) || "aiResourceView".equals(type);
            assertEquals(type, metadataRead, schema.isCreate());
            assertFalse(schema.isUpdate()); assertFalse(schema.isDeletable()); assertFalse(field.isUpdate());
            if (!metadataRead) { assertFalse(schema.isUpdate()); assertFalse(schema.isDeletable()); assertFalse(field.isCreate()); }
        }
    }
}
