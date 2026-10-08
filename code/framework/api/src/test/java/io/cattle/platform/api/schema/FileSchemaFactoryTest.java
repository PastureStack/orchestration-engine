package io.cattle.platform.api.schema;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.AbstractSchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.url.UrlBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class FileSchemaFactoryTest {

    private ClassLoader originalClassLoader;

    @Before
    public void captureClassLoader() {
        originalClassLoader = Thread.currentThread().getContextClassLoader();
    }

    @After
    public void restoreClassLoader() {
        Thread.currentThread().setContextClassLoader(originalClassLoader);
    }

    @Test
    public void readsSerializedSchemaList() throws Exception {
        String resourceName = "schemas/test-schema.bin";
        SchemaImpl schema = schema("machine", "machines");
        Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName,
                serialize(Arrays.<Object>asList(schema))));

        FileSchemaFactory factory = factory(resourceName);

        factory.start();

        assertEquals(1, factory.listSchemas().size());
        Schema loaded = factory.listSchemas().get(0);
        assertEquals("machine", loaded.getId());
        assertSame(loaded, factory.getSchema("machine"));
        assertSame(loaded, factory.getSchema("machines"));
        assertEquals("schema", loaded.getType());
    }

    @Test(expected = ClassCastException.class)
    public void rejectsSerializedListWithNonSchemaElement() throws Exception {
        String resourceName = "schemas/invalid-schema.bin";
        Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName,
                serialize(Arrays.<Object>asList("not-a-schema"))));

        factory(resourceName).start();
    }

    @Test
    public void enrichesFrozenV1ProjectMemberIdentityTypesFromCoreSchema() throws Exception {
        String resourceName = "schemas/v1-project-member.bin";
        SchemaImpl frozen = schema("projectMember", "projectMembers");
        FieldImpl frozenField = new FieldImpl();
        frozenField.setOptions(new ArrayList<String>(Arrays.asList(
                "rancher_id", "openldap_user", "openldap_group")));
        frozen.getResourceFields().put("externalIdType", frozenField);

        SchemaImpl core = schema("projectMember", "projectMembers");
        FieldImpl coreField = new FieldImpl();
        coreField.setOptions(new ArrayList<String>(Arrays.asList(
                "rancher_id", "oidc_user", "oidc_group")));
        core.getResourceFields().put("externalIdType", coreField);

        Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName,
                serialize(Arrays.<Object>asList(frozen))));
        FileSchemaFactory factory = factory(resourceName, new SingleSchemaFactory(core));

        factory.start();

        assertEquals(Arrays.asList("rancher_id", "openldap_user", "openldap_group",
                "oidc_user", "oidc_group"), factory.getSchema("projectMember")
                .getResourceFields().get("externalIdType").getOptions());
    }

    @Test
    public void exposesFrozenV1ProjectTemplatePublicStateWithoutGrantingWrites() throws Exception {
        String resourceName = "schemas/v1-project-template-user.bin";
        SchemaImpl frozen = schema("projectTemplate", "projectTemplates");
        SchemaImpl core = schema("projectTemplate", "projectTemplates");
        FieldImpl coreField = new FieldImpl();
        coreField.setName("isPublic");
        coreField.setType("boolean");
        coreField.setCreate(true);
        coreField.setUpdate(true);
        core.getResourceFields().put("isPublic", coreField);

        Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName,
                serialize(Arrays.<Object>asList(frozen))));
        FileSchemaFactory factory = factory(resourceName, new SingleSchemaFactory(core));
        factory.start();

        FieldImpl loaded = (FieldImpl) factory.getSchema("projectTemplate")
                .getResourceFields().get("isPublic");
        assertNotNull(loaded);
        assertNotSame(coreField, loaded);
        assertEquals("boolean", loaded.getType());
        assertTrue(loaded.isIncludeInList());
        assertFalse(loaded.isCreate());
        assertFalse(loaded.isUpdate());
        assertFalse(loaded.isReadOnCreateOnly());
        assertTrue("The current core schema must not be modified", coreField.isCreate());
        assertTrue(coreField.isUpdate());
    }

    @Test
    public void preservesExistingFrozenV1AdminProjectTemplatePermissions() throws Exception {
        String resourceName = "schemas/v1-project-template-admin.bin";
        SchemaImpl frozen = schema("projectTemplate", "projectTemplates");
        FieldImpl frozenField = new FieldImpl();
        frozenField.setCreate(true);
        frozenField.setUpdate(true);
        frozen.getResourceFields().put("isPublic", frozenField);
        SchemaImpl core = schema("projectTemplate", "projectTemplates");
        core.getResourceFields().put("isPublic", new FieldImpl());

        Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName,
                serialize(Arrays.<Object>asList(frozen))));
        FileSchemaFactory factory = factory(resourceName, new SingleSchemaFactory(core));
        factory.start();

        FieldImpl loaded = (FieldImpl) factory.getSchema("projectTemplate")
                .getResourceFields().get("isPublic");
        assertTrue(loaded.isCreate());
        assertTrue(loaded.isUpdate());
    }

    @Test
    public void repairsThePackagedFrozenV1UserSchemaNotOnlySyntheticFixtures() throws Exception {
        String resourceName = "schema/v1/user.ser";
        Path current = Paths.get("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(
                current.resolve("resources/content/").resolve(resourceName))) {
            current = current.getParent();
        }
        assertNotNull("Unable to locate the packaged frozen v1 schema", current);

        SchemaImpl core = schema("projectTemplate", "projectTemplates");
        FieldImpl coreField = new FieldImpl();
        coreField.setName("isPublic");
        coreField.setType("boolean");
        coreField.setCreate(true);
        coreField.setUpdate(true);
        core.getResourceFields().put("isPublic", coreField);

        Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName,
                Files.readAllBytes(current.resolve("resources/content/").resolve(resourceName))));
        FileSchemaFactory factory = factory(resourceName, new SingleSchemaFactory(core));
        factory.start();

        FieldImpl loaded = (FieldImpl) factory.getSchema("projectTemplate")
                .getResourceFields().get("isPublic");
        assertNotNull(loaded);
        assertFalse(loaded.isCreate());
        assertFalse(loaded.isUpdate());
        assertEquals("boolean", loaded.getType());
    }

    @Test
    public void doesNotWidenOptionsOnUnrelatedFrozenSchemas() throws Exception {
        String resourceName = "schemas/v1-account.bin";
        SchemaImpl frozen = schema("account", "accounts");
        FieldImpl frozenField = new FieldImpl();
        frozenField.setOptions(new ArrayList<String>(Arrays.asList("legacy")));
        frozen.getResourceFields().put("kind", frozenField);

        SchemaImpl core = schema("account", "accounts");
        FieldImpl coreField = new FieldImpl();
        coreField.setOptions(new ArrayList<String>(Arrays.asList("legacy", "new")));
        core.getResourceFields().put("kind", coreField);

        Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName,
                serialize(Arrays.<Object>asList(frozen))));
        FileSchemaFactory factory = factory(resourceName, new SingleSchemaFactory(core));

        factory.start();

        assertEquals(Arrays.asList("legacy"), factory.getSchema("account")
                .getResourceFields().get("kind").getOptions());
    }

    private FileSchemaFactory factory(String resourceName) {
        return factory(resourceName, new EmptySchemaFactory());
    }

    @Test
    public void restoresOnlyNativeReadFieldInPackagedFrozenVolumeRoleSchemas() throws Exception {
        SchemaImpl core = schema("volume", "volumes");
        FieldImpl coreField = new FieldImpl();
        coreField.setName("isNative");
        coreField.setType("boolean");
        coreField.setDefault(Boolean.FALSE);
        // Even a more permissive parent cannot grant writes through this merge.
        coreField.setCreate(true);
        coreField.setUpdate(true);
        core.getResourceFields().put("isNative", coreField);
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/v1/owner.ser"))) {
            root = root.getParent();
        }
        assertNotNull("Packaged frozen role schemas are required", root);
        for (String role : Arrays.asList("owner", "member", "readonly", "restricted", "user", "admin")) {
            String resourceName = "schema/v1/" + role + ".ser";
            byte[] bytes = Files.readAllBytes(root.resolve("resources/content/").resolve(resourceName));
            Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName, bytes));
            FileSchemaFactory original = factory(resourceName);
            original.start();
            Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName, bytes));
            FileSchemaFactory repaired = factory(resourceName, new SingleSchemaFactory(core));
            repaired.start();

            Schema before = original.getSchema("volume"), after = repaired.getSchema("volume");
            assertNotNull(role, before);
            assertNotNull(role, after);
            Map<String, FieldImpl> originalFields = new LinkedHashMap<String, FieldImpl>();
            for (Map.Entry<String, io.github.ibuildthecloud.gdapi.model.Field> entry : before.getResourceFields().entrySet()) {
                originalFields.put(entry.getKey(), (FieldImpl) entry.getValue());
            }
            FieldImpl actual = (FieldImpl) after.getResourceFields().get("isNative");
            assertNotNull(role, actual);
            assertEquals(role, "boolean", actual.getType());
            assertEquals(role, Boolean.FALSE, actual.getDefault());
            assertFalse(role, actual.isNullable());
            assertFalse(role, actual.isCreate());
            assertFalse(role, actual.isUpdate());
            assertFalse(role, actual.isReadOnCreateOnly());
            assertTrue(role, actual.isIncludeInList());
            assertEquals(role, before.getCollectionMethods(), after.getCollectionMethods());
            assertEquals(role, before.getResourceMethods(), after.getResourceMethods());
            assertTrue(role + "/resourceActions", Arrays.equals(
                    serialize(Arrays.<Object>asList(before.getResourceActions())),
                    serialize(Arrays.<Object>asList(after.getResourceActions()))));
            assertTrue(role + "/collectionActions", Arrays.equals(
                    serialize(Arrays.<Object>asList(before.getCollectionActions())),
                    serialize(Arrays.<Object>asList(after.getCollectionActions()))));
            for (Map.Entry<String, FieldImpl> entry : originalFields.entrySet()) {
                if (!"isNative".equals(entry.getKey())) {
                    assertTrue(role, after.getResourceFields().containsKey(entry.getKey()));
                    assertTrue(role + "/" + entry.getKey(), Arrays.equals(
                            serialize(Arrays.<Object>asList(entry.getValue())),
                            serialize(Arrays.<Object>asList(after.getResourceFields().get(entry.getKey())))));
                }
            }
            assertEquals(role, originalFields.size() + (originalFields.containsKey("isNative") ? 0 : 1),
                    after.getResourceFields().size());
        }
        assertTrue("Parent permissions must remain unchanged", coreField.isCreate());
        assertTrue(coreField.isUpdate());
    }

    private FileSchemaFactory factory(String resourceName, SchemaFactory schemaFactory) {
        FileSchemaFactory factory = new FileSchemaFactory();
        factory.setFile(resourceName);
        factory.setSchemaFactory(schemaFactory);
        return factory;
    }

    @Test
    public void preservesCreateOnlyApiKeyFlagInThePackagedFrozenV1UserSchema() throws Exception {
        String resourceName = "schema/v1/user.ser";
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/").resolve(resourceName))) {
            root = root.getParent();
        }
        assertNotNull("Packaged frozen v1 schema is required", root);
        Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName,
                Files.readAllBytes(root.resolve("resources/content/").resolve(resourceName))));
        FileSchemaFactory factory = factory(resourceName);
        factory.start();

        Schema keySchema = factory.getSchema("apiKey");
        assertNotNull(keySchema);
        FieldImpl field = (FieldImpl) keySchema.getResourceFields().get("secretValue");
        assertNotNull(field);
        assertTrue("Frozen v1 must retain the o overlay, not only the dynamic schema", field.isReadOnCreateOnly());
        assertTrue(field.isIncludeInList());
    }

    private SchemaImpl schema(String id, String pluralName) {
        SchemaImpl schema = new SchemaImpl();
        schema.setId(id);
        schema.setPluralName(pluralName);
        schema.setList(false);
        Map<String, URL> links = new LinkedHashMap<String, URL>();
        links.put(UrlBuilder.SELF, null);
        schema.setLinks(links);
        return schema;
    }

    @Test
    public void v1KeyPolicyContractMatchesCoreWhileFrozenFieldsAndPermissionsStayIntact() throws Exception {
        for (boolean writable : List.of(true, false)) {
            SchemaImpl frozen = schema("apiKey", "apiKeys");
            frozen.setCollectionMethods(writable ? new ArrayList<>(List.of("GET", "POST")) : new ArrayList<>(List.of("GET")));
            frozen.setResourceMethods(writable ? new ArrayList<>(List.of("GET", "PUT", "DELETE")) : new ArrayList<>(List.of("GET")));
            FieldImpl frozenSecret = new FieldImpl();
            frozenSecret.setType("password"); frozenSecret.setReadOnCreateOnly(true);
            frozen.getResourceFields().put("secretValue", frozenSecret);
            FieldImpl frozenName = new FieldImpl(); frozenName.setCreate(writable); frozenName.setUpdate(writable);
            frozen.getResourceFields().put("name", frozenName);
            byte[] originalSecret = serialize(List.of(frozenSecret));
            byte[] originalName = serialize(List.of(frozenName));
            SchemaImpl core = schema("apiKey", "apiKeys");
            FieldImpl policy = new FieldImpl(); policy.setType("map[json]"); policy.setCreate(true); policy.setUpdate(true);
            core.getResourceFields().put("apiKeyPolicy", policy);
            FieldImpl revision = new FieldImpl(); revision.setType("int"); revision.setUpdate(true);
            core.getResourceFields().put("apiKeyPolicyRevision", revision);
            FieldImpl ticket = new FieldImpl(); ticket.setType("password"); ticket.setCreate(true); ticket.setUpdate(true); ticket.setIncludeInList(false);
            core.getResourceFields().put("securityConfirmation", ticket);
            SchemaImpl restrictedCore = schema("apiKeyRestricted", "apiKeyRestricteds");
            restrictedCore.getResourceFields().putAll(core.getResourceFields());
            SchemaImpl previewCore = schema("apiKeyPolicyPreview", "apiKeyPolicyPreviews");
            previewCore.setCollectionMethods(new ArrayList<>(List.of("POST")));
            previewCore.getResourceFields().put("apiKeyPolicy", new FieldImpl(policy));
            Map<String, Schema> cores = Map.of(core.getId(), core, restrictedCore.getId(), restrictedCore,
                    previewCore.getId(), previewCore);
            SchemaFactory parent = new EmptySchemaFactory() {
                @Override public Schema getSchema(String type) { return cores.get(type); }
            };
            String resourceName = "schemas/api-key-policy-v1-" + writable + ".bin";
            Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName, serialize(List.of(frozen))));
            FileSchemaFactory factory = factory(resourceName, parent); factory.start();
            Schema loaded = factory.getSchema("apiKey");
            assertTrue(Arrays.equals(originalSecret, serialize(List.of(loaded.getResourceFields().get("secretValue")))));
            assertTrue(Arrays.equals(originalName, serialize(List.of(loaded.getResourceFields().get("name")))));
            assertEquals(frozen.getResourceMethods(), loaded.getResourceMethods());
            assertEquals(frozen.getCollectionMethods(), loaded.getCollectionMethods());
            for (String name : List.of("apiKeyPolicy", "apiKeyPolicyRevision", "securityConfirmation")) {
                Field field = loaded.getResourceFields().get(name);
                assertEquals(core.getResourceFields().get(name).getType(), field.getType());
                assertEquals(writable && core.getResourceFields().get(name).isCreate(), field.isCreate());
                assertEquals(writable && core.getResourceFields().get(name).isUpdate(), field.isUpdate());
                assertNotSame(core.getResourceFields().get(name), field);
            }
            Schema restricted = factory.getSchema("apiKeyRestricted");
            assertNotNull(restricted);
            assertEquals(loaded.getResourceMethods(), restricted.getResourceMethods());
            assertEquals(loaded.getResourceFields().keySet(), restricted.getResourceFields().keySet());
            assertEquals(writable, factory.getSchema("apiKeyPolicyPreview") != null);
            assertTrue(policy.isCreate()); assertTrue(policy.isUpdate());
        }
    }

    private byte[] serialize(List<Object> schemas) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(schemas);
        oos.close();
        return baos.toByteArray();
    }

    @Test
    public void supplementsPackagedV1AuditMetadataWithoutGrantingSchemaAccessOrWrites() throws Exception {
        List<String> names = List.of("eventId", "keyId", "decision", "outcome", "httpStatus", "requestId",
                "actor", "targetType", "targetId", "operation", "policyRevision", "reason", "phase",
                "preview", "processId", "processName", "hostUuid", "failureCode");
        SchemaImpl core = schema("auditLog", "auditLogs");
        for (String name : names) {
            FieldImpl field = new FieldImpl();
            field.setType("preview".equals(name) ? "boolean" : "httpStatus".equals(name) ? "int" : "string");
            field.setCreate(true); field.setUpdate(true); field.setReadOnCreateOnly(true);
            core.getResourceFields().put(name, field);
        }
        // A new arbitrary parent field must not leak into the frozen contract.
        core.getResourceFields().put("sensitivePayload", new FieldImpl());
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("resources/content/schema/v1/admin.ser"))) {
            root = root.getParent();
        }
        assertNotNull("Packaged role schemas are required", root);
        int auditedRoles = 0;
        for (String role : List.of("admin", "user", "owner", "member", "restricted", "readonly")) {
            String resourceName = "schema/v1/" + role + ".ser";
            byte[] bytes = Files.readAllBytes(root.resolve("resources/content/").resolve(resourceName));
            Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName, bytes));
            FileSchemaFactory original = factory(resourceName); original.start();
            Thread.currentThread().setContextClassLoader(new ResourceClassLoader(resourceName, bytes));
            FileSchemaFactory repaired = factory(resourceName, new SingleSchemaFactory(core)); repaired.start();
            Schema before = original.getSchema("auditLog"), after = repaired.getSchema("auditLog");
            assertEquals(role, original.listSchemas().size(), repaired.listSchemas().size());
            if (before == null) {
                assertTrue(role + " must not acquire audit access", after == null);
                continue;
            }
            auditedRoles++;
            assertNotNull(role, after);
            assertEquals(role, before.getCollectionMethods(), after.getCollectionMethods());
            assertEquals(role, before.getResourceMethods(), after.getResourceMethods());
            assertTrue(Arrays.equals(serialize(List.of(before.getResourceActions())), serialize(List.of(after.getResourceActions()))));
            assertTrue(Arrays.equals(serialize(List.of(before.getCollectionActions())), serialize(List.of(after.getCollectionActions()))));
            for (Map.Entry<String, Field> entry : before.getResourceFields().entrySet()) {
                assertTrue(role + "/" + entry.getKey(), Arrays.equals(serialize(List.of(entry.getValue())),
                        serialize(List.of(after.getResourceFields().get(entry.getKey())))));
            }
            for (String name : names) {
                Field field = after.getResourceFields().get(name);
                assertNotNull(role + "/" + name, field);
                if (before.getResourceFields().containsKey(name)) continue;
                assertNotSame(core.getResourceFields().get(name), field);
                assertEquals(core.getResourceFields().get(name).getType(), field.getType());
                assertFalse(field.isCreate()); assertFalse(field.isUpdate());
                assertFalse(field.isReadOnCreateOnly()); assertTrue(field.isIncludeInList());
            }
            assertFalse(after.getResourceFields().containsKey("sensitivePayload"));
        }
        assertTrue("The real frozen audit schemas must be exercised", auditedRoles > 0);
        assertTrue(core.getResourceFields().get("keyId").isCreate());
        assertTrue(core.getResourceFields().get("keyId").isUpdate());
    }

    private static class ResourceClassLoader extends ClassLoader {
        private final String resourceName;
        private final byte[] resource;

        ResourceClassLoader(String resourceName, byte[] resource) {
            this.resourceName = resourceName;
            this.resource = resource;
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (resourceName.equals(name)) {
                return new ByteArrayInputStream(resource);
            }
            return super.getResourceAsStream(name);
        }
    }

    private static class EmptySchemaFactory extends AbstractSchemaFactory {
        @Override
        public String getId() {
            return "empty";
        }

        @Override
        public List<Schema> listSchemas() {
            return Collections.emptyList();
        }

        @Override
        public Schema getSchema(Class<?> clz) {
            return null;
        }

        @Override
        public Schema getSchema(String type) {
            return null;
        }

        @Override
        public Class<?> getSchemaClass(String type) {
            return null;
        }

        @Override
        public Schema registerSchema(Object obj) {
            return null;
        }

        @Override
        public Schema parseSchema(String name) {
            return null;
        }
    }

    private static class SingleSchemaFactory extends EmptySchemaFactory {
        private final Schema schema;

        SingleSchemaFactory(Schema schema) {
            this.schema = schema;
        }

        @Override
        public Schema getSchema(String type) {
            return schema.getId().equalsIgnoreCase(type) ? schema : null;
        }
    }
}
