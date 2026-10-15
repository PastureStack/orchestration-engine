package io.cattle.platform.api.schema;

import static org.junit.Assert.*;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class OptInFrozenSchemaFieldsTest {
    @Test public void explicitAdditionIsBoundedByExistingFrozenHttpMethods() {
        SchemaImpl core=core();
        for(boolean writable:List.of(true,false)){
            SchemaImpl frozen=new SchemaImpl();frozen.setId("oldType");
            frozen.setCollectionMethods(new ArrayList<>(writable?List.of("GET","POST"):List.of("GET")));
            frozen.setResourceMethods(new ArrayList<>(writable?List.of("GET","PUT"):List.of("GET")));
            new FileSchemaFactory().mergeOptInFields(frozen,core);
            Field field=frozen.getResourceFields().get("newFlag");assertNotNull(field);
            assertEquals(writable,field.isCreate());assertEquals(writable,field.isUpdate());assertEquals("boolean",field.getType());
            assertFalse(frozen.getResourceFields().containsKey("notOptedIn"));
            assertEquals(writable?List.of("GET","PUT"):List.of("GET"),frozen.getResourceMethods());
        }
    }

    @Test public void existingFieldsAreNotReplacedAndNoOptInLeavesSchemaUntouched(){
        SchemaImpl core=core(),frozen=new SchemaImpl();frozen.setId("oldType");
        FieldImpl existing=new FieldImpl();existing.setType("string");frozen.getResourceFields().put("newFlag",existing);
        new FileSchemaFactory().mergeOptInFields(frozen,core);
        assertEquals(1,frozen.getResourceFields().size());assertSame(existing,frozen.getResourceFields().get("newFlag"));
        core.getResourceFields().remove("newFlag");
        SchemaImpl untouched=new SchemaImpl();new FileSchemaFactory().mergeOptInFields(untouched,core);
        assertTrue(untouched.getResourceFields().isEmpty());
    }

    private static SchemaImpl core(){
        SchemaImpl s=new SchemaImpl();s.setId("currentType");
        FieldImpl field=new FieldImpl();field.setType("boolean");field.setCreate(true);field.setUpdate(true);
        field.getAttributes().put("extendFrozenSchema",true);s.getResourceFields().put("newFlag",field);
        FieldImpl ordinary=new FieldImpl();ordinary.setType("string");s.getResourceFields().put("notOptedIn",ordinary);return s;
    }
}
