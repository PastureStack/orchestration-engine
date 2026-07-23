package io.cattle.platform.json;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.introspect.AnnotationIntrospectorPair;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.module.jakarta.xmlbind.JakartaXmlBindAnnotationIntrospector;

/**
 * Default implementation of JsonMapper that uses Jackson for marshaling and
 * supports JAXB annotations.
 */
public class JacksonJsonMapper implements JsonMapper {

    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP = new TypeReference<Map<String, Object>>() {
    };

    ObjectMapper mapper;
    List<Module> modules;

    public JacksonJsonMapper() {
        mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);

        AnnotationIntrospector primary = new JacksonAnnotationIntrospector();
        AnnotationIntrospector secondary = new JakartaXmlBindAnnotationIntrospector(mapper.getTypeFactory());

        AnnotationIntrospector pair = AnnotationIntrospectorPair.create(primary, secondary);
        mapper.setAnnotationIntrospector(pair);
        addOptionalMixIn("org.jooq.StoreQuery", JooqStoreQueryMixin.class);
        addOptionalMixIn("org.jooq.QualifiedRecord", JooqQualifiedRecordMixin.class);
    }

    @PostConstruct
    public void init() {
        if (modules != null) {
            for (Module module : modules) {
                mapper.registerModule(module);
            }
        }
    }

    public JacksonJsonMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public <T> T readValue(InputStream is, Class<T> type) throws IOException {
        return mapper.readValue(is, type);
    }

    @Override
    public <T> T readValue(byte[] bytes, Class<T> type) throws IOException {
        return mapper.readValue(bytes, type);
    }

    @Override
    public <T> T readValue(String text, Class<T> type) throws IOException {
        return mapper.readValue(text, type);
    }

    @Override
    public String writeValueAsString(Object object) throws IOException {
        return mapper.writeValueAsString(object);
    }

    @Override
    public byte[] writeValueAsBytes(Object data) throws IOException {
        return mapper.writeValueAsBytes(data);
    }

    @Override
    public void writeValue(OutputStream baos, Object object) throws IOException {
        mapper.writeValue(baos, object);
    }

    @Override
    public Map<String, Object> writeValueAsMap(Object data) {
        if (data == null) {
            return null;
        }
        return mapper.convertValue(data, STRING_OBJECT_MAP);
    }

    @Override
    public <E, C extends Collection<E>> C readCollectionValue(String content, Class<C> collectionClass, Class<E> elementsClass) throws IOException {
        CollectionType type = mapper.getTypeFactory().constructCollectionType(collectionClass, elementsClass);
        return mapper.readValue(content, type);
    }

    @Override
    public <E> List<E> readListValue(String content, Class<E> elementsClass) throws IOException {
        CollectionType type = mapper.getTypeFactory().constructCollectionType(List.class, elementsClass);
        return mapper.readValue(content, type);
    }

    @Override
    public <T> T convertValue(Object fromValue, Class<T> toValueType) {
        if (fromValue == null)
            return null;

        if (toValueType.isInstance(fromValue))
            return toValueType.cast(fromValue);

        return mapper.convertValue(fromValue, toValueType);

    }

    @Override
    public <E, C extends Collection<E>> C convertCollectionValue(Object fromValue, Class<C> collectionClass, Class<E> elementsClass) {
        CollectionType type = mapper.getTypeFactory().constructCollectionType(collectionClass, elementsClass);
        return mapper.convertValue(fromValue, type);
    }

    @Override
    public <E> List<E> convertListValue(Object fromValue, Class<E> elementsClass) {
        CollectionType type = mapper.getTypeFactory().constructCollectionType(List.class, elementsClass);
        return mapper.convertValue(fromValue, type);
    }

    public void setPrettyPrinting() {
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ObjectMapper getObjectMapper() {
        return mapper;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        this.mapper = objectMapper;
    }

    @Override
    public Map<String, Object> readValue(InputStream is) throws IOException {
        return mapper.readValue(is, STRING_OBJECT_MAP);
    }

    @Override
    public Map<String, Object> readValue(byte[] bytes) throws IOException {
        return mapper.readValue(bytes, STRING_OBJECT_MAP);
    }

    @Override
    public Map<String, Object> readValue(String text) throws IOException {
        return mapper.readValue(text, STRING_OBJECT_MAP);
    }

    @Override
    public <E, C extends Collection<E>> C readCollectionValue(InputStream is, Class<C> collectionClass, Class<E> elementsClass) throws IOException {
        CollectionType type = mapper.getTypeFactory().constructCollectionType(collectionClass, elementsClass);
        return mapper.readValue(is, type);
    }

    @Override
    public <E> List<E> readListValue(InputStream is, Class<E> elementsClass) throws IOException {
        CollectionType type = mapper.getTypeFactory().constructCollectionType(List.class, elementsClass);
        return mapper.readValue(is, type);
    }

    public List<Module> getModules() {
        return modules;
    }

    public void setModules(List<Module> modules) {
        this.modules = modules;
    }

    protected void addOptionalMixIn(String targetClassName, Class<?> mixinClass) {
        try {
            Class<?> targetClass = Class.forName(targetClassName);
            mapper.addMixIn(targetClass, mixinClass);
        } catch (ClassNotFoundException e) {
            // framework-json is used before framework-jooq in the Maven reactor.
        }
    }

    @JsonIgnoreProperties(value = { "returning" }, ignoreUnknown = true)
    static interface JooqStoreQueryMixin {
    }

    @JsonIgnoreProperties(value = { "qualifier" }, ignoreUnknown = true)
    static interface JooqQualifiedRecordMixin {
    }
}
