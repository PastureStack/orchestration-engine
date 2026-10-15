package io.cattle.platform.app;

import static org.junit.Assert.*;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.cattle.platform.schema.processor.NotWritablePostProcessor;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class ArchiveSchemaAuthorizationTest {
    @Test public void storedBooleanDoesNotScheduleRuntimeUpdatesAndUsesExistingRolePermissions() throws Exception {
        Path root=Paths.get("").toAbsolutePath();
        while(root!=null&&!Files.isRegularFile(root.resolve("resources/content/schema/base/stack.json")))root=root.getParent();
        assertNotNull(root);
        for(String id:List.of("stack","service")) {
            Map<String,Object> json;
            try(var in=Files.newInputStream(root.resolve("resources/content/schema/base/"+id+".json"))){json=new JacksonJsonMapper().readValue(in);}
            Map<?,?> field=(Map<?,?>)((Map<?,?>)json.get("resourceFields")).get("archived");
            assertEquals("boolean",field.get("type"));assertEquals(false,field.get("default"));assertEquals(false,field.get("nullable"));
            Map<?,?> attributes=(Map<?,?>)field.get("attributes");assertEquals(true,attributes.get("extendFrozenSchema"));
            assertFalse(attributes.containsKey("scheduleUpdate"));
            for(String role:List.of("owner","member","restricted","readonly")) {
                List<URL> overlays=new ArrayList<>();
                overlays.add(root.resolve("resources/content/schema/user/user-auth.json").toUri().toURL());
                overlays.add(root.resolve("resources/content/schema/project/project-auth.json").toUri().toURL());
                if(role.equals("owner"))overlays.add(root.resolve("resources/content/schema/owner/owner-auth.json").toUri().toURL());
                if(role.equals("restricted"))overlays.add(root.resolve("resources/content/schema/restricted-user/restricted-user.json").toUri().toURL());
                AuthOverlayPostProcessor auth=new AuthOverlayPostProcessor();auth.setJsonMapper(new JacksonJsonMapper());auth.setResources(overlays);auth.init();
                SchemaImpl schema=new SchemaImpl();schema.setId(id);FieldImpl flag=new FieldImpl();flag.setType("boolean");schema.getResourceFields().put("archived",flag);
                assertSame(schema,auth.postProcessRegister(schema,null));auth.postProcess(schema,null);
                if(role.equals("readonly"))new NotWritablePostProcessor().postProcessRegister(schema,null);
                Field actual=schema.getResourceFields().get("archived");assertNotNull(role+" "+id,actual);
                assertEquals(!role.equals("readonly"),actual.isUpdate());
                assertEquals(!role.equals("readonly"),schema.isUpdate());
            }
        }
    }
}
