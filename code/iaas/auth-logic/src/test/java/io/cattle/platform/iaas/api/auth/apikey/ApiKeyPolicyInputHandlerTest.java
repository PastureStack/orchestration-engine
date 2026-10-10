package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;

import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.api.schema.FileSchemaFactory;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.BodyParserRequestHandler;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Exercise parsed raw input before the real V1/V2 schema sanitizer and policy manager. */
public class ApiKeyPolicyInputHandlerTest {
    private final ApiKeyPolicyCodec codec = new ApiKeyPolicyCodec();
    private final ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();
    private URLClassLoader loader;
    private SchemaFactoryImpl core;
    private Path root;

    @Before public void schemas() throws Exception {
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/v1/user.ser"))) root = root.getParent();
        assertNotNull(root);
        loader = new URLClassLoader(new java.net.URL[]{root.resolve("resources/content").toUri().toURL()}, originalLoader);
        Thread.currentThread().setContextClassLoader(loader);
        core = new ApiKeyV1ReadContractTest().core();
        for (String type : List.of("apiKey", "apiKeyRestricted")) {
            ((SchemaImpl) core.getSchema(type)).setCollectionMethods(List.of("GET", "POST"));
            ((SchemaImpl) core.getSchema(type)).setResourceMethods(List.of("GET", "PUT", "DELETE"));
            field((SchemaImpl) core.getSchema(type), "apiKeyPolicy", "map[json]", true, true);
            field((SchemaImpl) core.getSchema(type), "name", "string", true, true);
            field((SchemaImpl) core.getSchema(type), "description", "string", true, true);
            field((SchemaImpl) core.getSchema(type), "apiKeyPolicyRevision", "int", false, true);
            field((SchemaImpl) core.getSchema(type), "securityConfirmation", "password", true, true);
            ((FieldImpl) core.getSchema(type).getResourceFields().get("accountId")).setUpdate(false);
        }
        SchemaImpl preview = (SchemaImpl) core.registerSchema("apiKeyPolicyPreview");
        preview.setCollectionMethods(List.of("POST")); preview.setResourceMethods(List.of());
        field(preview, "apiKeyId", "string", true, false);
        field(preview, "apiKeyPolicy", "map[json]", true, false);
        field(preview, "apiKeyPolicyRevision", "int", true, false);
    }

    @After public void cleanup() throws Exception {
        ApiContext.remove(); Thread.currentThread().setContextClassLoader(originalLoader);
        if (loader != null) loader.close();
    }

    @Test public void sanitizerAloneMasksForgedOwnerDataAndUnknownFields() throws Exception {
        for (String version : List.of("v1", "v2-beta")) {
            for (String field : List.of("accountId", "data", "unknownPolicyInput")) {
                ApiRequest request = parsed(version, "apiKeyRestricted", "PUT", policyInput(field, "forged"));
                validate(request);
                assertFalse(version + ":" + field, ((Map<?, ?>) request.getRequestObject()).containsKey(field));
            }
        }
    }

    @Test public void rawPolicyUpdateIsRejectedBeforeSanitizationOrAnyWrite() throws Exception {
        for (String version : List.of("v1", "v2-beta")) {
            for (String type : List.of("apiKey", "apikeys", "apiKeyRestricted", "apikeyrestricteds")) {
                for (String field : List.of("accountId", "data", "secretValue", "publicValue", "unknownPolicyInput")) {
                    Fixture fixture = new Fixture();
                    ApiRequest request = parsed(version, type, "PUT", policyInput(field, "forged"));
                    ClientVisibleException error = assertThrows(ClientVisibleException.class, () -> fixture.guard.handle(request));
                    assertEquals(400, error.getStatus()); assertEquals("ApiKeyPolicyInvalid", error.getCode());
                    assertTrue(((Map<?, ?>) request.getRequestObject()).containsKey(field));
                    assertEquals(0, fixture.writes.get()); assertEquals(Long.valueOf(77), fixture.key.getAccountId());
                    assertEquals(0, codec.revision(fixture.key));
                }
            }
        }
    }

    @Test public void legitimatePolicyUpdateSurvivesRealParserAndV1V2Validation() throws Exception {
        for (String version : List.of("v1", "v2-beta")) {
            Fixture fixture = new Fixture();
            ApiRequest request = parsed(version, "apiKey", "PUT", policyInput("name", "updated"));
            fixture.guard.handle(request); validate(request);
            fixture.service.update("apiKey", request.getId(), request, fixture.next);
            assertEquals(1, fixture.writes.get()); assertEquals(Long.valueOf(77), fixture.key.getAccountId());
            assertEquals(ApiKeyPolicy.Mode.CLOSED, codec.read(fixture.key).getMode());
            assertEquals(1, codec.revision(fixture.key)); assertEquals("updated", fixture.key.getName());
        }
    }

    @Test public void unmodifiedLegacyFullInputStillUsesExistingSanitization() throws Exception {
        for (String version : List.of("v1", "v2-beta")) {
            Fixture fixture = new Fixture();
            ApiRequest request = parsed(version, "apiKey", "PUT", Map.of("name", "legacy", "accountId", "1a999", "id", "1c12"));
            Map<?, ?> raw = new HashMap<>((Map<?, ?>) request.getRequestObject());
            fixture.guard.handle(request); assertEquals(raw, request.getRequestObject());
            validate(request); fixture.service.update("apiKey", request.getId(), request, fixture.next);
            assertEquals(1, fixture.writes.get()); assertEquals(Long.valueOf(77), fixture.key.getAccountId());
            assertNull(codec.read(fixture.key)); assertEquals(0, codec.revision(fixture.key));
        }
    }

    @Test public void policyCreateRejectsRawServerDataButKeepsDerivedOwnerSemantics() throws Exception {
        for (String version : List.of("v1", "v2-beta")) {
            Fixture fixture = new Fixture();
            ApiRequest bad = parsed(version, "apiKey", "POST", policyInput("data", Map.of("ownerAccountId", 999)));
            assertEquals("ApiKeyPolicyInvalid", assertThrows(ClientVisibleException.class, () -> fixture.guard.handle(bad)).getCode());
            Map<String, Object> nullType = new HashMap<>(Map.of("apiKeyPolicy", closed())); nullType.put("type", null);
            ApiRequest nullRequest = parsed(version, "apiKey", "POST", nullType);
            assertEquals("ApiKeyPolicyInvalid", assertThrows(ClientVisibleException.class, () -> fixture.guard.handle(nullRequest)).getCode());
            Map<String, Object> create = new HashMap<>(Map.of("type", "apiKey", "apiKeyPolicy", closed(), "accountId", "1a999"));
            ApiRequest good = parsed(version, "apiKey", "POST", create);
            fixture.guard.handle(good); validate(good);
            CredentialRecord created = new CredentialRecord(); fixture.service.prepareCreate(created, good);
            assertEquals(Long.valueOf(42), created.getAccountId()); assertEquals(1, codec.revision(created));
        }
    }

    @Test public void previewRejectsForgedServerFieldsBeforeTheyAreDropped() throws Exception {
        for (String version : List.of("v1", "v2-beta")) {
            for (String field : List.of("accountId", "requestDigest", "purpose", "confirmationRequired")) {
                Fixture fixture = new Fixture();
                ApiRequest request = parsed(version, "apiKeyPolicyPreview", "POST", Map.of("apiKeyPolicy", closed(), field, "forged"));
                assertEquals("ApiKeyPolicyInvalid", assertThrows(ClientVisibleException.class, () -> fixture.guard.handle(request)).getCode());
            }
            Fixture fixture = new Fixture();
            ApiRequest valid = parsed(version, "apiKeyPolicyPreview", "POST", Map.of("apiKeyPolicy", closed()));
            fixture.guard.handle(valid); validate(valid);
            assertTrue(((Map<?, ?>) valid.getRequestObject()).containsKey("apiKeyPolicy"));
        }
    }

    @Test public void unrelatedCredentialsAndReadActionHandledResponsesAreNotChanged() throws Exception {
        core.registerSchema("agentApiKey,parent=credential"); core.registerSchema("usernamePassword,parent=credential");
        for (String type : List.of("agentApiKey", "usernamePassword")) {
            Fixture fixture = new Fixture(); ApiRequest request = parsed("v2-beta", type, "PUT", policyInput("accountId", "1a999"));
            Map<?, ?> raw = new HashMap<>((Map<?, ?>) request.getRequestObject()); fixture.guard.handle(request);
            assertEquals(raw, request.getRequestObject());
        }
        for (String skip : List.of("GET", "action", "handled")) {
            Fixture fixture = new Fixture(); ApiRequest request = parsed("v2-beta", "apiKey", "PUT", policyInput("accountId", "1a999"));
            if ("GET".equals(skip)) request.setMethod("GET");
            if ("action".equals(skip)) request.setAction("deactivate");
            if ("handled".equals(skip)) request.setResponseObject(Map.of());
            fixture.guard.handle(request);
        }
    }

    @Test public void configuredHandlerIsAfterAuthenticationAndBeforeSanitization() throws Exception {
        String chain = Files.readString(root.resolve("code/packaging/app-config/src/main/resources/META-INF/cattle/iaas-api/defaults.properties"));
        assertTrue(chain.indexOf("ApiAuthenticator,") < chain.indexOf("ApiKeyPolicyInputHandler,"));
        assertTrue(chain.indexOf("ApiKeyPolicyInputHandler,") < chain.indexOf("ValidationHandler,"));
    }

    private SchemaFactory schemas(String version) throws Exception {
        if (!"v1".equals(version)) return core;
        FileSchemaFactory frozen = new FileSchemaFactory(); frozen.setSchemaFactory(core);
        frozen.setFile("schema/v1/user.ser"); frozen.start(); return frozen;
    }

    private ApiRequest parsed(String version, String type, String method, Map<String, Object> input) throws Exception {
        ApiContext.remove();
        byte[] bytes = new ObjectMapper().writeValueAsBytes(input);
        ApiRequest request = new ApiRequest(null, null) { @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); } };
        request.setSchemaFactory(schemas(version)); request.setVersion(version); request.setType(type); request.setMethod(method);
        request.setRequestParams(new HashMap<>());
        request.setResponseUrlBase("http://example.invalid");
        if ("PUT".equals(method)) request.setId("1c12");
        JacksonMapper mapper = new JacksonMapper(); mapper.init();
        BodyParserRequestHandler parser = new BodyParserRequestHandler(); parser.init(); parser.setJsonMarshaller(mapper); parser.handle(request);
        DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(request.getSchemaFactory());
        ApiContext context = ApiContext.newContext(); context.setApiRequest(request); context.setIdFormatter(formatter);
        return request;
    }

    private void validate(ApiRequest request) throws Exception { ValidationHandler handler = new ValidationHandler(); handler.init(); handler.handle(request); }
    private Map<String, Object> policyInput(String field, Object value) {
        Map<String, Object> input = new HashMap<>(Map.of("apiKeyPolicy", closed(), "apiKeyPolicyRevision", 0)); input.put(field, value); return input;
    }
    private Map<String, Object> closed() { return Map.of("mode", "closed", "defaultEffect", "deny", "rules", List.of()); }
    private void field(SchemaImpl schema, String name, String type, boolean create, boolean update) {
        FieldImpl field = new FieldImpl(); field.setName(name); field.setType(type); field.setCreate(create); field.setUpdate(update); field.setNullable(true);
        schema.getResourceFields().put(name, field);
    }

    private class Fixture {
        final AtomicInteger writes = new AtomicInteger();
        final CredentialRecord key = new CredentialRecord();
        final ApiKeyPolicyManagementService service;
        final ApiKeyPolicyInputHandler guard = new ApiKeyPolicyInputHandler();
        final ResourceManager next;
        Fixture() {
            key.setId(12L); key.setAccountId(77L); key.setKind("apiKey"); key.setState("active");
            service = new ApiKeyPolicyManagementService() {
                @Override protected Account actor() { AccountRecord actor = new AccountRecord(); actor.setId(42L); actor.setState("active"); return actor; }
                @Override protected void persist(Credential current, Map<String, Object> data, String kind, Map<String, Object> input) {
                    writes.incrementAndGet(); current.setData(data); current.setKind(kind);
                    if (input.containsKey("name")) current.setName((String) input.get("name"));
                }
            };
            service.objectManager = (ObjectManager) Proxy.newProxyInstance(originalLoader, new Class<?>[]{ObjectManager.class}, (proxy, method, args) -> "reload".equals(method.getName()) ? args[0] : null);
            service.lockManager = (LockManager) Proxy.newProxyInstance(originalLoader, new Class<?>[]{LockManager.class}, (proxy, method, args) -> ((LockCallback<?>) args[1]).doWithLock());
            next = (ResourceManager) Proxy.newProxyInstance(originalLoader, new Class<?>[]{ResourceManager.class}, (proxy, method, args) -> "getById".equals(method.getName()) ? key : null);
            guard.policies = service;
        }
    }
}
