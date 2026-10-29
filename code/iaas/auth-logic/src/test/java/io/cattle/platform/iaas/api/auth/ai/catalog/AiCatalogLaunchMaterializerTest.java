package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.SecretRecord;
import io.cattle.platform.docker.transform.DockerTransformer;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.util.ArrayList;
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
}
