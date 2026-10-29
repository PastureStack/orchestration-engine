package io.cattle.platform.app;

import static org.junit.Assert.*;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.cattle.platform.schema.processor.NotWritablePostProcessor;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class AiCatalogLaunchSchemaAuthorizationTest {
    @Test public void allRealRoleOverlaysKeepInputsAndActionOnlyTypeHasNoIndependentRoute() throws Exception {
        JacksonJsonMapper json = new JacksonJsonMapper(); Path root = root();
        SchemaFactory parents = (SchemaFactory) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { SchemaFactory.class }, (p, m, a) -> null);
        for (String role : List.of("admin", "user", "project")) {
            AuthOverlayPostProcessor overlay = new AuthOverlayPostProcessor(); overlay.setJsonMapper(json);
            overlay.setResources(List.of(root.resolve("resources/content/schema/" + role + "/" + role + "-auth.json").toUri().toURL())); overlay.init();
            for (String type : List.of("aiCatalogStackLaunchOptions", "aiCatalogStackLaunchPlan", "aiCatalogStackLaunchInputs")) {
                SchemaImpl schema = definition(root, type, json);
                Set<String> inputFields = schema.getResourceFields().entrySet().stream().filter(entry -> entry.getValue().isCreate())
                        .map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
                assertNotNull(role + ":" + type, overlay.postProcessRegister(schema, parents)); overlay.postProcess(schema, parents);
                for (String field : inputFields) {
                    assertNotNull(role + ":" + type + "." + field, schema.getResourceFields().get(field));
                    assertTrue(schema.getResourceFields().get(field).isCreate());
                }
                assertFalse(schema.isUpdate()); assertFalse(schema.isDeletable());
                if (type.endsWith("Inputs")) { assertEquals(List.of(), schema.getCollectionMethods()); assertEquals(List.of(), schema.getResourceMethods()); }
                else assertTrue(schema.getCollectionMethods().contains("POST"));
                if (type.endsWith("Plan")) {
                    assertEquals(Set.of("completeInputs", "confirm", "apply", "cancel"), schema.getResourceActions().keySet());
                    assertEquals("aiCatalogStackLaunchInputs", schema.getResourceActions().get("completeInputs").getInput());
                    for (String field : List.of("summary", "changes", "missingHumanInputs", "createdResource", "confirmationState", "inputsReady"))
                        assertFalse(schema.getResourceFields().get(field).isCreate());
                }
                new NotWritablePostProcessor().postProcessRegister(schema, parents);
                assertEquals(type.endsWith("Options"), schema.isCreate());
            }
        }
    }

    @Test public void actualSpringConfigurationRegistersDedicatedTypesManagersAndPrivateExecutionHooks() throws Exception {
        IaasApiConfig config = new IaasApiConfig();
        Set<String> types = config.ApiKeyPolicyTypes().getTypeClasses().stream().map(Class::getSimpleName).collect(java.util.stream.Collectors.toSet());
        assertTrue(types.containsAll(Set.of("AiCatalogStackLaunchOptions", "AiCatalogStackLaunchPlan", "AiCatalogStackLaunchInputs")));
        for (String bean : List.of("AiCatalogLaunchAccess", "AiCatalogLaunchPlanStore", "AiCatalogLaunchOptionsResourceManager",
                "AiCatalogLaunchPlanResourceManager", "AiCatalogLaunchBinding", "AiCatalogLaunchDelegation", "AiCatalogLaunchResponseFilter"))
            assertNotNull(IaasApiConfig.class.getDeclaredMethod(bean).getAnnotation(org.springframework.context.annotation.Bean.class));
        Map<String, Object> options = jsonMap(root().resolve("resources/content/schema/base/aiCatalogStackLaunchOptions.json"));
        assertTrue(map(options.get("resourceFields")).containsKey("requiredOperations"));
        String properties = Files.readString(root().resolve("resources/content/cattle-global.properties"));
        assertTrue(properties.contains("api.ai.catalog.launch.enabled=false"));
    }

    @Test public void catalogBeanDependencyClosureIncludesTheNativeMaterializer() throws Exception {
        IaasApiConfig config = new IaasApiConfig();
        Map<Class<?>, java.lang.reflect.Method> beans = new LinkedHashMap<>();
        for (var method : IaasApiConfig.class.getDeclaredMethods()) {
            if (method.getAnnotation(org.springframework.context.annotation.Bean.class) != null
                    && method.getReturnType().getPackageName().equals("io.cattle.platform.iaas.api.auth.ai.catalog")) {
                assertNull("Duplicate Catalog bean type", beans.put(method.getReturnType(), method));
                method.setAccessible(true);
                assertEquals(method.getReturnType(), method.invoke(config).getClass());
            }
        }
        assertTrue("The native launch materializer must be a real Spring bean",
                beans.containsKey(io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchMaterializer.class));
        for (Class<?> type : beans.keySet()) {
            for (var field : type.getDeclaredFields()) {
                if (field.getAnnotation(jakarta.inject.Inject.class) != null
                        && field.getType().getPackageName().equals("io.cattle.platform.iaas.api.auth.ai.catalog")) {
                    assertTrue(type.getSimpleName() + "." + field.getName() + " has no registered Catalog dependency",
                            beans.containsKey(field.getType()));
                }
            }
        }
    }

    private static SchemaImpl definition(Path root, String type, JacksonJsonMapper json) throws Exception {
        Map<String, Object> definition = json.readValue(Files.readString(root.resolve("resources/content/schema/base/" + type + ".json")));
        SchemaImpl schema = new SchemaImpl(); schema.setId(type);
        schema.setCollectionMethods(strings(definition.get("collectionMethods"))); schema.setResourceMethods(strings(definition.get("resourceMethods")));
        for (var entry : map(definition.get("resourceFields")).entrySet()) {
            Map<String, Object> spec = map(entry.getValue()); FieldImpl field = new FieldImpl(); field.setType((String) spec.get("type"));
            field.setCreate(Boolean.TRUE.equals(spec.get("create"))); field.setUpdate(Boolean.TRUE.equals(spec.get("update")));
            schema.getResourceFields().put(entry.getKey(), field);
        }
        Map<String, Action> actions = new LinkedHashMap<>();
        if (definition.get("resourceActions") != null) for (var entry : map(definition.get("resourceActions")).entrySet()) {
            Map<String, Object> spec = map(entry.getValue()); actions.put(entry.getKey(), new Action((String) spec.get("input"), (String) spec.get("output")));
        }
        schema.setResourceActions(actions); return schema;
    }
    private static Map<String, Object> jsonMap(Path path) throws Exception { return new JacksonJsonMapper().readValue(Files.readString(path)); }
    private static Map<String, Object> map(Object value) {
        assertTrue(value instanceof Map<?, ?>); Map<String, Object> result = new LinkedHashMap<>();
        for (var entry : ((Map<?, ?>) value).entrySet()) { assertTrue(entry.getKey() instanceof String); result.put((String) entry.getKey(),
                entry.getValue()); }
        return result;
    }
    private static List<String> strings(Object value) {
        assertTrue(value instanceof List<?>); List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) { assertTrue(item instanceof String); result.add((String) item); } return result;
    }
    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("resources/content/schema/base"))) path = path.getParent();
        assertNotNull(path); return path;
    }
}
