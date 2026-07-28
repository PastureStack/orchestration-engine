package io.github.ibuildthecloud.gdapi.validation;

import static org.junit.Assert.*;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.RequestUtils;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler.ValidationContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class ValidationHandlerTest {

    @Test
    public void testNullableOption() {
        SchemaImpl schema = new SchemaImpl();
        FieldImpl field = new FieldImpl();
        field.setType("array[enum]");
        field.setOptions(Arrays.asList("one", "two"));
        field.setCreate(true);
        field.setNullable(true);

        schema.getResourceFields().put("test", field);

        ApiRequest request = new ApiRequest(null, null);
        ValidationContext context = new ValidationContext();
        ValidationHandler handler = new ValidationHandler();

        Map<String, Object> input = new HashMap<String, Object>();
        input.put("test", null);
        request.setRequestObject(input);

        handler.validateOperationField(schema, request, true, context);
    }

    @Test
    public void testListEnum() {
        SchemaImpl schema = new SchemaImpl();
        FieldImpl field = new FieldImpl();
        field.setType("array[enum]");
        field.setOptions(Arrays.asList("one", "two"));
        field.setCreate(true);

        schema.getResourceFields().put("test", field);

        ApiRequest request = new ApiRequest(null, null);
        ValidationContext context = new ValidationContext();
        ValidationHandler handler = new ValidationHandler();

        Map<String, Object> input = new HashMap<String, Object>();
        input.put("test", "three");
        request.setRequestObject(input);

        try {
            handler.validateOperationField(schema, request, true, context);
            fail();
        } catch (ClientVisibleException e) {
            assertEquals(ValidationErrorCodes.INVALID_OPTION, e.getApiError().getCode());
        }

        input.put("test", "one");
        handler.validateOperationField(schema, request, true, context);
    }

    @Test
    public void testCreateSubtype() {
        SchemaFactoryImpl factory = new SchemaFactoryImpl();
        factory.getTypes().add(ParentType.class);
        factory.getTypes().add(SubType.class);
        factory.init();

        ApiRequest request = new ApiRequest(null, null);
        ValidationContext context = new ValidationContext();
        ValidationHandler handler = new ValidationHandler();

        context.schemaFactory = factory;

        Map<String, Object> childType = new HashMap<String, Object>();
        childType.put("testField", "abc");
        childType.put("notWrite", "xyz");

        Map<String, Object> input = new HashMap<String, Object>();
        input.put("subType", childType);

        request.setRequestObject(input);

        handler.validateOperationField(factory.getSchema(ParentType.class), request, true, context);

        Map<String, Object> result = RequestUtils.toMap(request.getRequestObject());
        Map<?, ?> childData = (Map<?, ?>)result.get("subType");

        assertTrue(childData != null);
    }

    @Test
    public void testCreateArraySubtype() {
        SchemaFactoryImpl factory = new SchemaFactoryImpl();
        factory.getTypes().add(ParentType.class);
        factory.getTypes().add(SubType.class);
        factory.init();

        ApiRequest request = new ApiRequest(null, null);
        ValidationContext context = new ValidationContext();
        ValidationHandler handler = new ValidationHandler();

        context.schemaFactory = factory;

        Map<String, Object> childType = new HashMap<String, Object>();
        childType.put("testField", "abc");
        childType.put("notWrite", "xyz");

        Map<String, Object> childType2 = new HashMap<String, Object>();
        childType2.put("testField", "abc2");
        childType2.put("notWrite", "xyz");

        Map<String, Object> input = new HashMap<String, Object>();
        input.put("subTypes", Arrays.asList(childType, childType2));

        request.setRequestObject(input);

        handler.validateOperationField(factory.getSchema(ParentType.class), request, true, context);

        Map<String, Object> result = RequestUtils.toMap(request.getRequestObject());
        List<?> childData = (List<?>)result.get("subTypes");

        assertTrue(childData != null);
        assertEquals("abc", ((Map<?, ?>)childData.get(0)).get("testField"));
        assertEquals("abc2", ((Map<?, ?>)childData.get(1)).get("testField"));
    }

    @Test
    public void testCreateMapSubtypeConvertsValuesAndPreservesKeys() {
        SchemaImpl schema = new SchemaImpl();
        FieldImpl field = new FieldImpl();
        field.setType("map[int]");
        field.setCreate(true);

        schema.getResourceFields().put("settings", field);

        ApiRequest request = new ApiRequest(null, null);
        ValidationContext context = new ValidationContext();
        ValidationHandler handler = new ValidationHandler();

        Map<String, Object> settings = new HashMap<String, Object>();
        settings.put("first", "1");
        settings.put("second", 2);

        Map<String, Object> input = new HashMap<String, Object>();
        input.put("settings", settings);
        request.setRequestObject(input);

        handler.validateOperationField(schema, request, true, context);

        Map<String, Object> result = RequestUtils.toMap(request.getRequestObject());
        Map<?, ?> sanitizedSettings = (Map<?, ?>)result.get("settings");

        assertEquals(Long.valueOf(1), sanitizedSettings.get("first"));
        assertEquals(Long.valueOf(2), sanitizedSettings.get("second"));
    }

}
