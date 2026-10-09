package io.cattle.platform.api.schema;

import io.cattle.platform.util.type.InitializationTask;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.AbstractSchemaFactory;
import io.github.ibuildthecloud.gdapi.factory.impl.SubSchemaFactory;
import io.github.ibuildthecloud.gdapi.json.JsonMapper;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import org.apache.commons.lang3.StringUtils;

public class FileSchemaFactory extends AbstractSchemaFactory implements InitializationTask {

    @Inject
    JsonMapper jsonMapper;
    @Inject @Named("CoreSchemaFactory")
    SchemaFactory schemaFactory;
    String file, id;
    Map<String, Schema> schemaMap = new TreeMap<>();
    Map<String, Class<?>> schemaClasses = new HashMap<>();
    List<Schema> schemas = new ArrayList<>();
    boolean init;

    @Override
    public synchronized void start() {
        if (init) {
            return;
        }

        if (schemaFactory instanceof SubSchemaFactory) {
            ((SubSchemaFactory) schemaFactory).init();
        }

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try(InputStream is = cl.getResourceAsStream(file)) {
            ObjectInputStream ois = new ObjectInputStream(is);
            for (Schema schema : readSchemas(ois)) {
                SchemaImpl.class.cast(schema).setType("schema");
                schema.getActions().clear();
                schema.getLinks().clear();
                copyAccessors(schema);
                schemaMap.put(schema.getId().toLowerCase(), schema);
                if (StringUtils.isNotBlank(schema.getPluralName())) {
                    schemaMap.put(schema.getPluralName().toLowerCase(), schema);
                }
                schemas.add(schema);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }

        addApiKeyPolicySchemas();

        init = true;
    }

    private List<Schema> readSchemas(ObjectInputStream ois) throws ClassNotFoundException, IOException {
        List<?> serializedSchemas = List.class.cast(ois.readObject());
        List<Schema> result = new ArrayList<Schema>(serializedSchemas.size());
        for (Object schema : serializedSchemas) {
            result.add(Schema.class.cast(schema));
        }
        return result;
    }

    @PostConstruct
    protected void init() {
        if (this.id == null) {
            this.id = "v1-" + StringUtils.substringAfterLast(file, "/").split("[.]")[0];
        }
    }

    protected void copyAccessors(Schema schema) {
        SchemaFactory parentSchemaFactory = schemaFactory;
        Schema coreSchema = parentSchemaFactory.getSchema(schema.getId());
        mergeProjectMemberExternalIdTypeOptions(schema, coreSchema);
        mergeProjectTemplatePublicReadField(schema, coreSchema);
        mergeVolumeNativeReadField(schema, coreSchema);
        mergeApiKeyPolicyFields(schema, coreSchema);
        mergeApiKeyMfaPurposeOption(schema, coreSchema);
        mergeAuditLogReadFields(schema, coreSchema);
        Class<?> clz =  parentSchemaFactory.getSchemaClass(schema.getId());
        if (clz == null) {
            return;
        }

        schemaClasses.put(schema.getId().toLowerCase(), clz);

        Schema parentSchema = parentSchemaFactory.getSchema(clz);
        for (Map.Entry<String, Field> entry : schema.getResourceFields().entrySet()) {
            ((FieldImpl) entry.getValue()).setName(entry.getKey());
            Field parentField = parentSchema.getResourceFields().get(entry.getKey());
            if (parentField == null || !(parentField instanceof FieldImpl)) {
                continue;
            }
            ((FieldImpl) entry.getValue()).setReadMethod(((FieldImpl) parentField).getReadMethod());
        }
    }

    private void mergeApiKeyPolicyFields(Schema schema, Schema coreSchema) {
        if (coreSchema == null || !("apiKey".equals(schema.getId()) || "apiKeyRestricted".equals(schema.getId()))) {
            return;
        }
        for (String name : List.of("apiKeyPolicy", "apiKeyPolicyRevision", "securityConfirmation")) {
            Field coreField = coreSchema.getResourceFields().get(name);
            if (!(coreField instanceof FieldImpl) || schema.getResourceFields().containsKey(name)) continue;
            FieldImpl field = new FieldImpl(coreField);
            field.setName(name);
            field.setCreate(field.isCreate() && schema.getCollectionMethods().contains("POST"));
            field.setUpdate(field.isUpdate() && schema.getResourceMethods().contains("PUT"));
            schema.getResourceFields().put(name, field);
        }
    }

    private void mergeApiKeyMfaPurposeOption(Schema schema, Schema coreSchema) {
        if (coreSchema == null || !"mfaOperation".equals(schema.getId())
                || !schema.getCollectionMethods().contains("POST")) return;
        Field field = schema.getResourceFields().get("purpose");
        Field coreField = coreSchema.getResourceFields().get("purpose");
        if (!(field instanceof FieldImpl) || !field.isCreate() || coreField == null
                || coreField.getOptions() == null || !coreField.getOptions().contains("apiKeyPolicyUpdate")
                || (field.getOptions() != null && field.getOptions().contains("apiKeyPolicyUpdate"))) return;
        // Extend only the purpose already writable by this frozen role, without
        // importing other core options or changing any field permissions.
        List<String> options = field.getOptions() == null ? new ArrayList<>() : new ArrayList<>(field.getOptions());
        options.add("apiKeyPolicyUpdate");
        ((FieldImpl) field).setOptions(options);
    }

    private void mergeAuditLogReadFields(Schema schema, Schema coreSchema) {
        if (coreSchema == null || !"auditLog".equals(schema.getId())) return;
        // Extend only an audit schema already granted by the frozen role. These
        // are server-owned, non-secret metadata; neither role access nor writes
        // are added, and existing fields/methods/actions remain untouched.
        for (String name : List.of("eventId", "keyId", "decision", "outcome", "httpStatus", "requestId",
                "actor", "targetType", "targetId", "operation", "policyRevision", "reason", "phase",
                "preview", "processId", "processName", "hostUuid", "failureCode")) {
            Field coreField = coreSchema.getResourceFields().get(name);
            if (!(coreField instanceof FieldImpl) || schema.getResourceFields().containsKey(name)) continue;
            FieldImpl field = new FieldImpl(coreField);
            field.setName(name);
            field.setCreate(false);
            field.setUpdate(false);
            field.setReadOnCreateOnly(false);
            field.setIncludeInList(true);
            schema.getResourceFields().put(name, field);
        }
    }

    /** Only these new contracts are added to the frozen v1 role schemas. */
    private void addApiKeyPolicySchemas() {
        Schema apiKey = getSchema("apiKey");
        if (apiKey == null) return;
        Schema restrictedCore = schemaFactory.getSchema("apiKeyRestricted");
        if (restrictedCore != null && getSchema("apiKeyRestricted") == null) {
            SchemaImpl restricted = new SchemaImpl();
            restricted.setId("apiKeyRestricted");
            restricted.setType("schema");
            restricted.setPluralName(restrictedCore.getPluralName());
            restricted.setParent("apiKey");
            restricted.setCollectionMethods(new ArrayList<>(apiKey.getCollectionMethods()));
            restricted.setResourceMethods(new ArrayList<>(apiKey.getResourceMethods()));
            restricted.setResourceActions(new HashMap<>(apiKey.getResourceActions()));
            restricted.setCollectionActions(new HashMap<>(apiKey.getCollectionActions()));
            apiKey.getResourceFields().forEach((name, field) -> restricted.getResourceFields().put(name, new FieldImpl(field)));
            addSupplementalSchema(restricted);
        }
        Schema previewCore = schemaFactory.getSchema("apiKeyPolicyPreview");
        if (previewCore != null && getSchema("apiKeyPolicyPreview") == null
                && (apiKey.getCollectionMethods().contains("POST") || apiKey.getResourceMethods().contains("PUT"))) {
            SchemaImpl preview = new SchemaImpl();
            preview.setId("apiKeyPolicyPreview");
            preview.setType("schema");
            preview.setPluralName(previewCore.getPluralName());
            preview.setCollectionMethods(new ArrayList<>(previewCore.getCollectionMethods()));
            preview.setResourceMethods(new ArrayList<>());
            previewCore.getResourceFields().forEach((name, field) -> preview.getResourceFields().put(name, new FieldImpl(field)));
            addSupplementalSchema(preview);
        }
    }

    private void addSupplementalSchema(SchemaImpl schema) {
        copyAccessors(schema);
        schemaMap.put(schema.getId().toLowerCase(), schema);
        if (StringUtils.isNotBlank(schema.getPluralName())) schemaMap.put(schema.getPluralName().toLowerCase(), schema);
        schemas.add(schema);
    }

    protected void mergeProjectMemberExternalIdTypeOptions(Schema schema, Schema parentSchema) {
        if (parentSchema == null || !"projectMember".equals(schema.getId())) {
            return;
        }

        Field field = schema.getResourceFields().get("externalIdType");
        Field parentField = parentSchema.getResourceFields().get("externalIdType");
        if (!(field instanceof FieldImpl) || parentField == null) {
            return;
        }

        LinkedHashSet<String> options = new LinkedHashSet<String>();
        if (field.getOptions() != null) {
            options.addAll(field.getOptions());
        }
        if (parentField.getOptions() != null) {
            options.addAll(parentField.getOptions());
        }
        ((FieldImpl) field).setOptions(new ArrayList<String>(options));
    }

    protected void mergeProjectTemplatePublicReadField(Schema schema, Schema parentSchema) {
        if (parentSchema == null || !"projectTemplate".equals(schema.getId()) ||
                schema.getResourceFields().containsKey("isPublic")) {
            return;
        }

        Field parentField = parentSchema.getResourceFields().get("isPublic");
        if (!(parentField instanceof FieldImpl)) {
            return;
        }

        // v1 loads frozen .ser schemas. The current user auth overlay grants
        // read access, but cannot add a field missing from those snapshots.
        // Copy only this public-state field and keep mutation admin-only.
        FieldImpl readOnly = new FieldImpl(parentField);
        readOnly.setName("isPublic");
        readOnly.setCreate(false);
        readOnly.setUpdate(false);
        readOnly.setReadOnCreateOnly(false);
        schema.getResourceFields().put("isPublic", readOnly);
    }

    public String getFile() {
        return file;
    }

    protected void mergeVolumeNativeReadField(Schema schema, Schema parentSchema) {
        if (parentSchema == null || !"volume".equals(schema.getId()) ||
                schema.getResourceFields().containsKey("isNative")) {
            return;
        }

        Field parentField = parentSchema.getResourceFields().get("isNative");
        if (!(parentField instanceof FieldImpl)) {
            return;
        }

        // v1 reads frozen role schemas, so the current authorization overlay
        // cannot restore this missing classification. Expose only the existing
        // server-owned flag; do not grant mutation or replace other fields.
        FieldImpl readOnly = new FieldImpl(parentField);
        readOnly.setName("isNative");
        readOnly.setCreate(false);
        readOnly.setUpdate(false);
        readOnly.setReadOnCreateOnly(false);
        readOnly.setIncludeInList(true);
        schema.getResourceFields().put("isNative", readOnly);
    }

    public void setFile(String file) {
        this.file = file;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public List<Schema> listSchemas() {
        return schemas;
    }

    @Override
    public Schema getSchema(String type) {
        if (type == null) {
            return null;
        }
        return schemaMap.get(type.toLowerCase());
    }

    @Override
    public Schema getSchema(Class<?> clz) {
        Schema s = schemaFactory.getSchema(clz);
        return s == null ? null : getSchema(s.getId());
    }

    @Override
    public Class<?> getSchemaClass(String type) {
        Schema schema = getSchema(type);
        return schema == null ? null : schemaFactory.getSchemaClass(schema.getId());
    }

    @Override
    public Schema registerSchema(Object obj) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Schema parseSchema(String name) {
        throw new UnsupportedOperationException();
    }

    public void setId(String id) {
        this.id = id;
    }

    public SchemaFactory getSchemaFactory() {
        return schemaFactory;
    }

    public void setSchemaFactory(SchemaFactory schemaFactory) {
        this.schemaFactory = schemaFactory;
    }

}
