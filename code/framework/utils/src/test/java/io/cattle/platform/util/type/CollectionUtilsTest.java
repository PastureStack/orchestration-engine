package io.cattle.platform.util.type;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.netflix.config.ConfigurationManager;

public class CollectionUtilsTest {

    private static final String PREFIX = "collection.utils.test.item";

    @After
    public void clearSettings() {
        clear(PREFIX + ".exclude");
        clear(PREFIX + ".include");
        clear(PREFIX + ".list");
    }

    @Test
    public void addToMapCreatesTypedCollectionAndReusesIt() {
        Map<String, List<Integer>> data = new LinkedHashMap<String, List<Integer>>();

        CollectionUtils.addToMap(data, "ids", 1, ArrayList::new);
        CollectionUtils.addToMap(data, "ids", 2, ArrayList::new);

        assertTrue(data.get("ids") instanceof ArrayList);
        assertEquals(Arrays.asList(1, 2), data.get("ids"));
    }

    @Test
    public void setNestedValueCreatesIntermediateMaps() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();

        CollectionUtils.setNestedValue(data, "value", "first", "second", "name");

        assertEquals("value", CollectionUtils.getNestedValue(data, "first", "second", "name"));
    }

    @Test
    public void setNestedValueReusesExistingIntermediateMap() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        Map<String, Object> existing = new LinkedHashMap<String, Object>();
        data.put("first", existing);

        CollectionUtils.setNestedValue(data, "value", "first", "name");

        assertEquals("value", existing.get("name"));
    }

    @Test
    public void setNestedValueStopsOnNullKey() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();

        CollectionUtils.setNestedValue(data, "value", "first", null, "name");

        assertTrue(CollectionUtils.toMap(data.get("first")).isEmpty());
    }

    @Test
    public void setNestedValueRejectsNonMapIntermediateValue() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("first", "not-a-map");

        try {
            CollectionUtils.setNestedValue(data, "value", "first", "name");
            fail("Expected non-map intermediate values to be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Expected [not-a-map] to be a Map"));
        }
    }

    @Test
    public void toMapReturnsSameMapForMapInput() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("name", "service");

        Map<String, Object> result = CollectionUtils.toMap(data);

        assertSame(data, result);
        assertEquals("service", result.get("name"));
    }

    @Test
    public void toMapPreservesErasedMixedEntriesWithoutEagerValidation() {
        Map<Object, Object> data = new LinkedHashMap<Object, Object>();
        Object key = new Object();
        Object value = new Object();
        data.put(key, value);
        data.put(42, "legacy");

        Map<String, Object> result = CollectionUtils.toMap(data);

        assertSame(data, result);
        assertSame(value, result.get(key));
        assertEquals("legacy", result.get(42));
    }

    @Test
    public void toMapReturnsEmptyMapForNonMapInput() {
        Map<String, Object> result = CollectionUtils.toMap("not-a-map");

        assertTrue(result.isEmpty());
    }

    @Test
    public void castMapRejectsNonMapInput() {
        try {
            CollectionUtils.castMap("not-a-map");
            fail("Expected non-map values to be rejected");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Expected [not-a-map] to be a Map"));
        }
    }

    @Test
    public void castMapReturnsSameWritableBackingMapForMapInput() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();

        Map<String, Object> result = CollectionUtils.castMap(data);
        result.put("name", "service");

        assertSame(data, result);
        assertEquals("service", data.get("name"));
    }

    @Test
    public void castMapPreservesErasedMixedEntriesWithoutEagerValidation() {
        Map<Object, Object> data = new LinkedHashMap<Object, Object>();
        Object key = new Object();
        Object value = new Object();
        data.put(key, value);
        data.put(42, "legacy");

        Map<String, Object> result = CollectionUtils.castMap(data);

        assertSame(data, result);
        assertSame(value, result.get(key));
        assertEquals("legacy", result.get(42));
    }

    @Test
    public void asMapPreservesTypedKeysAndRejectsMalformedPairs() {
        Map<String, Object> result = CollectionUtils.asMap("first", 1, "second", 2);

        assertEquals(1, result.get("first"));
        assertEquals(2, result.get("second"));

        try {
            CollectionUtils.asMap("first", 1, "second");
            fail("Expected malformed key/value pairs to be rejected");
        } catch (IllegalArgumentException e) {
            assertEquals("value[] must be not null and an odd length", e.getMessage());
        }
    }

    @Test
    public void asMapPreservesErasedMixedKeysWithoutEagerValidation() {
        Object key = new Object();

        Map<String, Object> result = CollectionUtils.asMap("first", 1, key, "legacy");

        assertEquals(1, result.get("first"));
        assertEquals("legacy", result.get(key));
    }

    @Test
    public void uncheckedCastBoundaryStaysPrivate() throws Exception {
        Map<Object, Object> data = new LinkedHashMap<Object, Object>();
        Method uncheckedCast = CollectionUtils.class.getDeclaredMethod("uncheckedCast", Object.class);

        assertTrue(Modifier.isPrivate(uncheckedCast.getModifiers()));
        assertTrue(Modifier.isStatic(uncheckedCast.getModifiers()));
        uncheckedCast.setAccessible(true);
        assertSame(data, uncheckedCast.invoke(null, data));
    }

    @Test
    public void orderListSortsByPriorityThenName() {
        List<CollectionUtilsTestItem> ordered = CollectionUtils.orderList(CollectionUtilsTestItem.class, Arrays.asList(
                new CollectionUtilsTestItem("gamma", Priority.DEFAULT),
                new CollectionUtilsTestItem("alpha", Priority.DEFAULT),
                new CollectionUtilsTestItem("beta", Priority.PRE)));

        assertNames(ordered, "beta", "alpha", "gamma");
    }

    @Test
    public void orderListPreservesConfiguredListAndExcludeBehavior() {
        ConfigurationManager.getConfigInstance().setProperty(PREFIX + ".list", "gamma,beta,missing,alpha");
        ConfigurationManager.getConfigInstance().setProperty(PREFIX + ".exclude", "beta");

        List<CollectionUtilsTestItem> ordered = CollectionUtils.orderList(CollectionUtilsTestItem.class, Arrays.asList(
                new CollectionUtilsTestItem("alpha", Priority.DEFAULT),
                new CollectionUtilsTestItem("beta", Priority.DEFAULT),
                new CollectionUtilsTestItem("gamma", Priority.DEFAULT)));

        assertNames(ordered, "gamma", "alpha");
    }

    @Test
    public void orderListRejectsDuplicateNamesWithSamePriority() {
        try {
            CollectionUtils.orderList(CollectionUtilsTestItem.class, Arrays.asList(
                    new CollectionUtilsTestItem("duplicate", Priority.DEFAULT),
                    new CollectionUtilsTestItem("duplicate", Priority.DEFAULT)));
            fail("Expected duplicate names to be rejected");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("Trying to add 2 objects with the same name: duplicate"));
        }
    }

    private static void clear(String key) {
        if (ConfigurationManager.getConfigInstance().containsKey(key)) {
            ConfigurationManager.getConfigInstance().clearProperty(key);
        }
    }

    private static void assertNames(List<CollectionUtilsTestItem> items, String... names) {
        assertEquals(names.length, items.size());
        for (int i = 0; i < names.length; i++) {
            assertEquals(names[i], items.get(i).getName());
        }
    }

    private static class CollectionUtilsTestItem implements Named, Priority {
        private final String name;
        private final int priority;

        CollectionUtilsTestItem(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getPriority() {
            return priority;
        }
    }
}
