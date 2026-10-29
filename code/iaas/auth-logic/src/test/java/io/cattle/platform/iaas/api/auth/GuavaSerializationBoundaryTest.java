package io.cattle.platform.iaas.api.auth;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.MapMaker;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import org.junit.Test;

/** Regression coverage for the official Guava fix for CVE-2026-102554. */
public class GuavaSerializationBoundaryTest {
    private static final List<String> COMPACT_TYPES = List.of(
            "CompactHashMap", "CompactLinkedHashMap", "CompactHashSet", "CompactLinkedHashSet");

    @Test public void inflatedCompactCollectionCountsFailWithoutEagerAllocation() throws Exception {
        for (String type : COMPACT_TYPES) {
            byte[] data = serialize(compactCollection(type));
            assertThrows(type, IOException.class, () -> deserialize(data, true));
        }
    }

    @Test public void ordinaryCompactCollectionsPreserveTheirEntries() throws Exception {
        for (String type : COMPACT_TYPES) {
            Object original = compactCollection(type);
            assertEquals(type, original, deserialize(serialize(original), false));
        }
    }

    @Test public void weakKeyMapIgnoresInflatedInitialCapacityAndPreservesEntries() throws Exception {
        ConcurrentMap<String, String> original = new MapMaker().weakKeys().makeMap();
        original.put("key", "value");
        for (boolean inflated : List.of(false, true)) {
            Map<?, ?> restored = (Map<?, ?>) deserialize(serialize(original), inflated);
            assertEquals(1, restored.size());
            assertEquals("key", restored.keySet().iterator().next());
            assertEquals("value", restored.values().iterator().next());
        }
    }

    @Test public void schemaCacheRetainsPutLookupAndInvalidation() {
        Cache<String, String> cache = CacheBuilder.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).build();
        cache.put("schema", "authorized");
        assertEquals("authorized", cache.getIfPresent("schema"));
        cache.invalidate("schema");
        assertNull(cache.getIfPresent("schema"));
    }

    private static Object compactCollection(String type) throws Exception {
        Class<?> clazz = Class.forName("com.google.common.collect." + type);
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object result = constructor.newInstance();
        if (result instanceof Map<?, ?>) {
            Method put = clazz.getMethod("put", Object.class, Object.class); put.setAccessible(true);
            put.invoke(result, "key", "value");
        } else if (result instanceof Set<?>) {
            Method add = clazz.getMethod("add", Object.class); add.setAccessible(true);
            add.invoke(result, "value");
        } else {
            throw new AssertionError(type);
        }
        return result;
    }

    private static byte[] serialize(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(value); }
        return bytes.toByteArray();
    }

    private static Object deserialize(byte[] value, boolean inflated) throws IOException, ClassNotFoundException {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(value)) {
            private boolean replaced;
            @Override public int readInt() throws IOException {
                int actual = super.readInt();
                if (inflated && !replaced) { replaced = true; return Integer.MAX_VALUE; }
                return actual;
            }
        }) { return input.readObject(); }
    }
}
