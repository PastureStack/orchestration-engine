package io.cattle.platform.db.jooq.converter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.Map;

import org.jooq.Converter;
import org.junit.Test;

public class DataConverterTest {

    @Test
    public void exposesStringToMapConverterTypesForJooqMetadata() {
        DataConverter converter = new DataConverter();

        assertEquals(String.class, converter.fromType());
        assertEquals(Map.class, converter.toType());
        assertNull(converter.from(null));
        assertNull(converter.to(null));
    }

    @Test
    public void keepsParameterizedMapConverterWhileExposingRawMapClassMetadata() {
        DataConverter converter = new DataConverter();
        ParameterizedType converterType = converterInterface();
        Type mapArgument = converterType.getActualTypeArguments()[1];

        assertEquals(Converter.class, converterType.getRawType());
        assertEquals(String.class, converterType.getActualTypeArguments()[0]);
        assertEquals(Map.class, converter.toType());

        ParameterizedType mapType = ParameterizedType.class.cast(mapArgument);
        assertEquals(Map.class, mapType.getRawType());
        assertEquals(String.class, mapType.getActualTypeArguments()[0]);
        assertEquals(Object.class, mapType.getActualTypeArguments()[1]);
    }

    @Test
    public void mapClassCastBoundaryStaysPrivate() throws Exception {
        Method method = DataConverter.class.getDeclaredMethod("mapType");

        assertTrue(Modifier.isPrivate(method.getModifiers()));
        assertTrue(Modifier.isStatic(method.getModifiers()));
        method.setAccessible(true);
        assertEquals(Map.class, method.invoke(null));
    }

    private static ParameterizedType converterInterface() {
        for (Type type : DataConverter.class.getGenericInterfaces()) {
            if (type instanceof ParameterizedType) {
                ParameterizedType parameterizedType = ParameterizedType.class.cast(type);
                if (parameterizedType.getRawType() == Converter.class) {
                    return parameterizedType;
                }
            }
        }

        throw new AssertionError("Expected DataConverter to implement Converter<String, Map<String, Object>>");
    }
}
