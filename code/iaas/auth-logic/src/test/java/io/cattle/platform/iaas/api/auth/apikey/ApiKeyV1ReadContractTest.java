package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static io.cattle.platform.core.model.tables.CredentialTable.CREDENTIAL;

import io.cattle.platform.api.auth.impl.NoPolicyOptions;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.api.resource.jooq.DefaultJooqResourceManager;
import io.cattle.platform.api.resource.jooq.AbstractJooqResourceManager;
import io.cattle.platform.api.schema.FileSchemaFactory;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.iaas.api.auth.impl.AccountPolicy;
import io.cattle.platform.iaas.api.infrastructure.InfrastructureAccessManager;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.meta.impl.DefaultObjectMetaDataManager;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.AbstractSchemaPostProcessor;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.ParseCollectionAttributes;
import io.github.ibuildthecloud.gdapi.request.handler.ResourceManagerRequestHandler;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.url.DefaultUrlBuilder;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.After;
import org.junit.Test;

public class ApiKeyV1ReadContractTest {
    private final ClassLoader originalLoader = Thread.currentThread().getContextClassLoader();

    @After public void clearContext() {
        ApiContext.remove();
        Thread.currentThread().setContextClassLoader(originalLoader);
    }

    @Test public void packagedV1RestrictedKeyWithoutSortReachesOwnerScopedLookupById() throws Exception {
        SchemaFactoryImpl core = core();
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/v1/user.ser"))) root = root.getParent();
        assertNotNull("Packaged frozen schemas are required", root);
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{root.resolve("resources/content").toUri().toURL()}, originalLoader)) {
            Thread.currentThread().setContextClassLoader(loader);
            for (String role : List.of("user", "admin")) {
                FileSchemaFactory v1 = new FileSchemaFactory();
                v1.setSchemaFactory(core); v1.setFile("schema/v1/" + role + ".ser"); v1.start();
                assertTrue(v1.getSchema("apiKeyRestricted").getResourceFields() instanceof TreeMap);
                assertEquals(v1.getSchema("apiKey").getResourceMethods(), v1.getSchema("apiKeyRestricted").getResourceMethods());
                // Preserve the existing frozen role contract, not a filter or RBAC workaround.
                assertTrue(v1.getSchema("apiKeyRestricted").getCollectionFilters().isEmpty());
                for (String alias : List.of("apiKeyRestricted", "apikeyrestricteds")) read(core, v1, "v1", alias);
            }
        }
    }

    @Test public void v2RestrictedKeyWithoutSortKeepsDefaultOrderingAndOwnerGuard() throws Exception {
        SchemaFactoryImpl core = core();
        for (String alias : List.of("apiKeyRestricted", "apikeyrestricteds")) read(core, core, "v2-beta", alias);
    }

    private void read(SchemaFactoryImpl core, SchemaFactory factory, String version, String alias) throws Exception {
        CredentialRecord key = new CredentialRecord();
        key.setId(7414L); key.setAccountId(2513L); key.setKind("apiKeyRestricted"); key.setState("inactive");
        key.setData(new HashMap<>());
        AccountRecord owner = new AccountRecord(); owner.setId(2513L);
        AccountPolicy policy = new AccountPolicy(owner, owner, Set.of(), new NoPolicyOptions());
        assertFalse(policy.isOption(AccountPolicy.AUTHORIZED_FOR_ALL_ACCOUNTS));
        ApiRequest request = new ApiRequest(null, null);
        request.setSchemaFactory(factory); request.setType(factory.getSchema(alias).getId()); request.setMethod("GET");
        request.setId("1c7414"); request.setVersion(version); request.setResponseUrlBase("http://example.invalid");
        request.setRequestUrl("http://example.invalid/" + version + "/" + alias + "/1c7414");
        request.setRequestObject(new HashMap<>()); request.setUrlBuilder(new DefaultUrlBuilder(request, factory));
        DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(factory);
        ApiContext context = ApiContext.newContext(); context.setApiRequest(request); context.setIdFormatter(formatter); context.setPolicy(policy);

        new ParseCollectionAttributes().handle(request);
        assertNull(request.getSort());
        ValidationHandler validation = new ValidationHandler(); validation.init(); validation.handle(request);
        assertEquals("7414", request.getId());

        CredentialMetadata metadata = new CredentialMetadata(); metadata.setSchemaFactory(core); metadata.registerCredentialFields();
        DefaultJooqResourceManager manager = new DefaultJooqResourceManager(); manager.setMetaDataManager(metadata);
        manager.setOutputFilterManager(resource -> null);
        var infrastructure = AbstractJooqResourceManager.class.getDeclaredField("infraAccess"); infrastructure.setAccessible(true);
        infrastructure.set(manager, Proxy.newProxyInstance(originalLoader, new Class<?>[]{InfrastructureAccessManager.class},
                (proxy, method, args) -> false));
        manager.setObjectManager((ObjectManager) Proxy.newProxyInstance(originalLoader, new Class<?>[]{ObjectManager.class}, (proxy, method, args) -> {
            if ("getSchemaFactory".equals(method.getName())) return core;
            throw new UnsupportedOperationException(method.getName());
        }));
        AtomicInteger lookups = new AtomicInteger();
        var records = DSL.using(SQLDialect.MARIADB);
        manager.setConfiguration(DSL.using(new MockConnection(sql -> {
            lookups.incrementAndGet();
            assertTrue(sql.sql(), sql.sql().contains("account_id"));
            assertTrue(sql.sql(), sql.sql().contains("kind"));
            assertTrue(sql.sql(), sql.sql().contains("order by"));
            assertTrue(sql.sql(), sql.sql().contains("id` asc"));
            List<Object> bindings = Arrays.asList(sql.bindings());
            assertTrue(bindings.toString(), bindings.contains(2513L));
            assertTrue(bindings.toString(), bindings.contains(7414L));
            assertTrue(bindings.toString(), bindings.contains("apiKeyRestricted"));
            var result = records.newResult(CREDENTIAL); result.add(key);
            return new MockResult[]{new MockResult(1, result)};
        }), SQLDialect.MARIADB).configuration());
        ResourceManagerRequestHandler handler = new ResourceManagerRequestHandler();
        handler.setResourceManagerLocator((ResourceManagerLocator) Proxy.newProxyInstance(originalLoader, new Class<?>[]{ResourceManagerLocator.class},
                (proxy, method, args) -> manager));
        handler.handle(request);
        assertEquals(1, lookups.get());
        assertTrue(request.getResponseObject() instanceof CredentialRecord);
        Resource resource = manager.convertResponse(request.getResponseObject(), request);
        assertEquals("1c7414", resource.getId()); assertEquals("apiKeyRestricted", resource.getType());
        assertEquals("1a2513", resource.getFields().get("accountId"));
        assertEquals("inactive", resource.getFields().get("state"));
        assertEquals("http://example.invalid/" + version + "/apikeyrestricteds/1c7414", resource.getLinks().get("self").toString());
        CredentialRecord foreign = new CredentialRecord(); foreign.setAccountId(999L);
        assertNull("The real owner guard must still reject foreign keys", policy.authorizeObject(foreign));
    }

    private SchemaFactoryImpl core() {
        SchemaFactoryImpl factory = new SchemaFactoryImpl(); factory.setId("core");
        factory.setPostProcessors(List.of(new AbstractSchemaPostProcessor() {
            @Override public SchemaImpl postProcessRegister(SchemaImpl schema, SchemaFactory parent) {
                if (schema.getId().endsWith("Record")) schema.setId(schema.getId().substring(0, schema.getId().length() - 6));
                return schema;
            }
        }));
        factory.setTypes(List.of(AccountRecord.class, CredentialRecord.class));
        factory.setTypeNames(List.of("apiKey,parent=credential", "apiKeyRestricted,parent=apiKey")); factory.init();
        // Match the production foreign-key field contract, not the bare bean's long type.
        for (String type : List.of("credential", "apiKey", "apiKeyRestricted")) {
            ((FieldImpl) factory.getSchema(type).getResourceFields().get("accountId")).setType("reference[account]");
        }
        return factory;
    }

    private static class CredentialMetadata extends DefaultObjectMetaDataManager {
        void registerCredentialFields() { registerTableFields(CREDENTIAL); }
    }
}
