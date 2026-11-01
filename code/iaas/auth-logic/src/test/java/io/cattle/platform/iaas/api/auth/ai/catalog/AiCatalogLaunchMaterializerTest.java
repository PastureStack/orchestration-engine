package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.addon.LogConfig;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.SecretRecord;
import io.cattle.platform.docker.transform.DockerTransformer;
import io.cattle.platform.docker.transform.DockerTransformerImpl;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.schema.processor.AuthOverlayPostProcessor;
import io.cattle.platform.schema.processor.JpaWritablePostProcessor;
import io.cattle.platform.schema.processor.JsonFileOverlayPostProcessor;
import io.cattle.platform.schema.processor.SchemaOverlayImpl;
import io.cattle.platform.schema.processor.StripSuffixPostProcessor;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.factory.impl.SubSchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.After;
import org.junit.Test;

public class AiCatalogLaunchMaterializerTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void preparedGraphSeparatesNativeMutationFromAuthorizedPublicReferenceIds() throws Exception {
        final String privateValue = "PRIVATE_SECRET_ROW_VALUE_MUST_NOT_LEAVE";
        var json = new JacksonJsonMapper();
        Policy owner = mock(Policy.class); when(owner.getAccountId()).thenReturn(5L);
        SchemaFactory schemas = mock(SchemaFactory.class);
        SchemaImpl container = new SchemaImpl(); container.setId("container");
        when(schemas.getSchema("container")).thenReturn(container);
        var authority = new AiCatalogLaunchAccess.Authorization(new ApiAuthenticator.CurrentAuthorization(owner, schemas), null);
        ObjectManager objects = mock(ObjectManager.class); ResourceManager manager = mock(ResourceManager.class);
        AccountRecord project = new AccountRecord(); project.setId(5L); project.setName("Default");
        when(objects.loadResource(Account.class, 5L)).thenReturn(project);
        SecretRecord secret = new SecretRecord(); secret.setId(7L); secret.setAccountId(5L); secret.setName("db-secret");
        secret.setValue(privateValue); when(manager.list(eq("secret"), any())).thenReturn(List.of(secret));
        when(manager.getById(eq("secret"), eq("7"), any())).thenReturn(secret);
        IdFormatter formatter = mock(IdFormatter.class);
        when(formatter.formatId("secret", 7L)).thenReturn("1se7"); when(formatter.parseId("1se7")).thenReturn("7");
        ApiContext.newContext().setIdFormatter(formatter);
        List<String> authorizedReads = new ArrayList<>();
        AiCatalogLaunchAccess access = new AiCatalogLaunchAccess() {
            @Override public <T> T domain(ApiRequest original, Authorization selected, String type, String method, String id, String action,
                    Map<String, Object> body, Function<ApiRequest, T> operation) {
                assertSame(authority, selected); assertEquals("secret", type); assertEquals("GET", method); assertNull(action);
                authorizedReads.add(type + ":" + id);
                ApiRequest nativeRead = new ApiRequest(null, schemas); nativeRead.setType(type); nativeRead.setMethod(method);
                nativeRead.setId(id == null ? null : formatter.parseId(id)); return operation.apply(nativeRead);
            }
            @Override ResourceManager manager(String type) { assertEquals("secret", type); return manager; }
            @Override void preflightChild(ApiRequest original, Authorization selected, Map<String, Object> plan, String type,
                    Map<String, Object> body) { assertSame(authority, selected); assertEquals("service", type); }
            @Override void childActions(Authorization selected, String type, boolean starts) {
                assertSame(authority, selected); assertEquals("service", type); assertFalse(starts);
            }
        };
        access.objects = objects;
        var materializer = new AiCatalogLaunchMaterializer(); materializer.access = access;
        materializer.transformer = mock(DockerTransformer.class); materializer.graphs = new AiCatalogLaunchGraph(); materializer.graphs.json = json;
        materializer.runner = mock(AiCatalogLaunchRunner.class);
        Map<String, Object> inspect = Map.of("name", "app", "dockerInspect", Map.of("Config", Map.of("Image", "busybox")));
        when(materializer.runner.run(anyMap())).thenAnswer(call -> {
            Map<String, Object> input = call.getArgument(0);
            if (!"finalize".equals(input.get("phase"))) return Map.of("services", List.of(inspect),
                    "secrets", Map.of("db-secret", Map.of("External", "db-secret")));
            assertEquals(Map.of("db-secret", "1se7"), input.get("resolvedSecrets"));
            assertFalse(json.writeValueAsString(input).contains(privateValue));
            return Map.of("phase", "finalize", "services", List.of(inspect), "serviceRequests", List.of(Map.of(
                    "name", "app", "resourceType", "service", "createBody", Map.of("name", "app", "stackId", "$createdStack"),
                    "setServiceLinks", Map.of("serviceLinks", List.of()))), "volumeRequests", List.of(),
                    "dockerCompose", "services: {}", "rancherCompose", "", "variables", Map.of());
        });
        var prepared = materializer.prepare(new ApiRequest(null, schemas), authority, Map.of("name", "chosen", "startOnCreate", false),
                Map.of("files", Map.of("docker-compose.yml", "services: {}")), Map.of());
        assertEquals(List.of("secret:null", "secret:1se7"), authorizedReads);
        verify(manager).getById(eq("secret"), eq("7"), any());
        assertEquals(Map.of("db-secret", "1se7"), prepared.authorizedReferenceIds());
        assertFalse(prepared.nativeGraph().containsKey("resolvedSecrets"));
        assertFalse(prepared.nativeGraph().containsKey("authorizedReferenceIds"));
        assertFalse(json.writeValueAsString(Map.of("graph", prepared.nativeGraph(), "ids", prepared.authorizedReferenceIds()))
                .contains(privateValue));
        assertThrows(UnsupportedOperationException.class, () -> prepared.authorizedReferenceIds().put("other", "1se8"));
        assertThrows(UnsupportedOperationException.class, () -> prepared.nativeGraph().put("resolvedSecrets", Map.of()));
    }

    @Test public void realTransformerDynamicFieldsReachNativeRoleSchemaProjection() throws Exception {
        var fixture = new ProjectionFixture(false);
        Map<String, Object> projected = fixture.prepare();
        assertEquals("docker:busybox:1.37.0", projected.get("imageUuid"));
        assertEquals(Map.of("PUBLIC_TEXT", "one=two"), projected.get("environment"));
        assertEquals(List.of("18080:80/tcp"), projected.get("ports"));
        assertEquals(Map.of("driver", "json-file", "config", Map.of("max-size", "10m")), projected.get("logConfig"));
        assertEquals("app", projected.get("name"));
        assertFalse(projected.containsKey("notInNativeSchema"));
        assertSame(fixture.schemas, fixture.authority.current().schemas());
        // Native bean accessors remain real; the four dynamic fields have no
        // InstanceRecord getter and must come from the data.fields value source.
        var fields = fixture.schemas.getSchema("container").getResourceFields();
        assertNotNull(((FieldImpl) fields.get("name")).getReadMethod());
        for (String field : List.of("imageUuid", "environment", "ports", "logConfig")) {
            assertNull(field, ((FieldImpl) fields.get(field)).getReadMethod());
            assertNotNull(field, DataUtils.getFields(fixture.transformer.instance).get(field));
        }
        // WrappedResource consumes only the fresh copy, never InstanceRecord's map.
        assertEquals(Map.of("PUBLIC_TEXT", "one=two"), DataUtils.getFields(fixture.transformer.instance).get("environment"));
    }

    @Test public void dynamicProjectionKeepsRestrictedSchemaAndNonCreateResponseRules() throws Exception {
        var fixture = new ProjectionFixture(true);
        Map<String, Object> projected = fixture.prepare();
        assertEquals("docker:busybox:1.37.0", projected.get("imageUuid"));
        assertEquals("app", projected.get("name"));
        assertFalse("A hidden schema field cannot reappear from additionalFields", projected.containsKey("environment"));
        assertNull("POST alone must not disclose a read-on-create-only field", projected.get("ports"));
        assertNull(projected.get("logConfig"));
        assertFalse(projected.containsKey("notInNativeSchema"));
        assertEquals(Map.of("PUBLIC_TEXT", "one=two"), DataUtils.getFields(fixture.transformer.instance).get("environment"));
        assertNotNull(DataUtils.getFields(fixture.transformer.instance).get("ports"));
        assertNotNull(DataUtils.getFields(fixture.transformer.instance).get("logConfig"));
    }

    private static final class RecordingTransformer extends DockerTransformerImpl {
        InstanceRecord instance;
        @Override public void transform(Map<String, Object> inspect, Instance target) {
            super.transform(inspect, target);
            instance = (InstanceRecord) target;
            DataAccessor.setField(target, "notInNativeSchema", "MUST_NOT_ESCAPE_SCHEMA");
        }
    }

    private static final class ProjectionFixture {
        final JacksonJsonMapper json = new JacksonJsonMapper();
        final SchemaFactory schemas;
        final AiCatalogLaunchAccess.Authorization authority;
        final RecordingTransformer transformer = new RecordingTransformer();
        final AiCatalogLaunchMaterializer materializer = new AiCatalogLaunchMaterializer();
        Map<String, Object> projection;

        ProjectionFixture(boolean restrict) throws Exception {
            schemas = nativeSchemas(json);
            if (restrict) {
                // Narrow a copy of the real role schema, retaining its real
                // accessors. These controls must still win over dynamic data.
                var fields = schemas.getSchema("container").getResourceFields();
                ((FieldImpl) fields.get("environment")).setIncludeInList(false);
                ((FieldImpl) fields.get("ports")).setReadOnCreateOnly(true);
                ((FieldImpl) fields.get("logConfig")).setReadOnCreateOnly(true);
            }
            Policy policy = mock(Policy.class); when(policy.getAccountId()).thenReturn(5L);
            authority = new AiCatalogLaunchAccess.Authorization(new ApiAuthenticator.CurrentAuthorization(policy, schemas), null);
            var formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("account", "a", "instance", "i", "stack", "st", "service", "s"));
            ApiContext.newContext().setIdFormatter(formatter);
            ObjectManager objects = mock(ObjectManager.class);
            AccountRecord project = new AccountRecord(); project.setId(5L); project.setName("Default");
            when(objects.loadResource(Account.class, 5L)).thenReturn(project);
            materializer.access = new AiCatalogLaunchAccess() {
                @Override void preflightChild(ApiRequest original, Authorization selected, Map<String, Object> plan,
                        String type, Map<String, Object> body) { assertSame(authority, selected); assertEquals("service", type); }
                @Override void childActions(Authorization selected, String type, boolean starts) {
                    assertSame(authority, selected); assertEquals("service", type); assertFalse(starts);
                }
            };
            materializer.access.objects = objects;
            var mapper = DockerTransformerImpl.class.getDeclaredField("jsonMapper");
            mapper.setAccessible(true); mapper.set(transformer, json);
            materializer.transformer = transformer;
            materializer.graphs = new AiCatalogLaunchGraph(); materializer.graphs.json = json;
            materializer.runner = mock(AiCatalogLaunchRunner.class);
            Map<String, Object> inspect = Map.of("name", "app", "dockerInspect", Map.of("Name", "app",
                "Config", Map.of("Image", "busybox:1.37.0", "Env", List.of("PUBLIC_TEXT=one=two"),
                    "ExposedPorts", Map.of("80/tcp", Map.of())),
                "HostConfig", Map.of("NetworkMode", "bridge",
                    "PortBindings", Map.of("80/tcp", List.of(Map.of("HostIp", "", "HostPort", "18080"))),
                    "LogConfig", Map.of("Type", "json-file", "Config", Map.of("max-size", "10m")))));
            when(materializer.runner.run(anyMap())).thenAnswer(call -> {
                Map<String, Object> input = call.getArgument(0);
                if (!"finalize".equals(input.get("phase"))) return Map.of("services", List.of(inspect));
                Map<String, Object> configs = AiCatalogLaunchInput.map(input.get("transformedLaunchConfigs"));
                projection = json.readValue(json.writeValueAsString(configs.get("app")));
                return Map.of("phase", "finalize", "services", List.of(inspect), "serviceRequests", List.of(Map.of(
                    "name", "app", "resourceType", "service", "createBody", Map.of("name", "app", "stackId", "$createdStack"),
                    "setServiceLinks", Map.of("serviceLinks", List.of()))), "volumeRequests", List.of(),
                    "dockerCompose", "services: {}", "rancherCompose", "", "variables", Map.of());
            });
        }

        Map<String, Object> prepare() {
            ApiRequest request = new ApiRequest(null, schemas); request.setMethod("POST");
            materializer.prepare(request, authority, Map.of("name", "chosen", "startOnCreate", false),
                Map.of("files", Map.of("docker-compose.yml", "services: {}")), Map.of());
            assertNotNull(projection);
            return projection;
        }
    }

    private static SchemaFactory nativeSchemas(JacksonJsonMapper json) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("resources/content/schema"))) root = root.getParent();
        assertNotNull("Native source schema definitions are required", root);
        final Path definitions = root.resolve("resources/content/schema");
        var schemaJson = new JacksonMapper(); schemaJson.init();
        // Same native field/parent construction and overlay implementation as
        // CoreSchemaFactory. Only source-tree URL discovery is local to this test.
        var base = new JsonFileOverlayPostProcessor() {
            @Override public SchemaImpl postProcessRegister(SchemaImpl schema, SchemaFactory factory) { return schema; }
            @Override public SchemaImpl postProcess(SchemaImpl schema, SchemaFactory factory) {
                Path file = definitions.resolve("base/"+schema.getId()+".json");
                if (!Files.isRegularFile(file)) return schema;
                try {
                    byte[] content = Files.readAllBytes(file);
                    processSchema(schema, schemaJson.readValue(content, SchemaOverlayImpl.class), json.readValue(content));
                    return schema;
                } catch (Exception failure) { throw new IllegalStateException(failure); }
            }
        };
        var suffix = new StripSuffixPostProcessor(); suffix.setSuffix("Record");
        var core = new SchemaFactoryImpl(); core.setTypes(List.of(InstanceRecord.class, LogConfig.class));
        core.setTypeNames(List.of("container,parent=instance"));
        core.setPostProcessors(List.of(suffix, new JpaWritablePostProcessor(), base)); core.init();
        var role = new AuthOverlayPostProcessor(); role.setJsonMapper(json);
        role.setResources(List.of(definitions.resolve("project/project-auth.json").toUri().toURL())); role.init();
        var selected = new SubSchemaFactory(); selected.setId("native-project-projection-test"); selected.setSchemaFactory(core);
        selected.setPostProcessors(List.of(role)); selected.init();
        assertNotNull(selected.getSchema("container"));
        for (String field : List.of("imageUuid", "environment", "ports", "logConfig", "name"))
            assertNotNull(field, selected.getSchema("container").getResourceFields().get(field));
        return selected;
    }
}
