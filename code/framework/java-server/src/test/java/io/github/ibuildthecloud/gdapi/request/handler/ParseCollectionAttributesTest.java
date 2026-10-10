package io.github.ibuildthecloud.gdapi.request.handler;

import static org.junit.Assert.*;

import io.github.ibuildthecloud.gdapi.condition.Condition;
import io.github.ibuildthecloud.gdapi.condition.ConditionType;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.Field;
import io.github.ibuildthecloud.gdapi.model.FieldType;
import io.github.ibuildthecloud.gdapi.model.Filter;
import io.github.ibuildthecloud.gdapi.model.Sort.SortOrder;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.url.DefaultUrlBuilder;
import io.github.ibuildthecloud.gdapi.util.DateUtils;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import java.text.ParseException;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ParseCollectionAttributesTest {
    private TimeZone originalTimeZone;

    @Before public void useNonUtcDefaultTimeZone() {
        originalTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Taipei"));
    }

    @After public void clearContext() {
        ApiContext.remove();
        TimeZone.setDefault(originalTimeZone);
    }

    @Test public void omittedSortDoesNotQueryATreeMapWithNull() throws Exception {
        ApiRequest request = request(Map.of());
        assertTrue(request.getSchemaFactory().getSchema("machine").getResourceFields() instanceof TreeMap);
        request.setId("12");
        new ParseCollectionAttributes().handle(request);
        assertNull(request.getSort());
        assertEquals(Integer.valueOf(100), request.getPagination().getLimit());
    }

    @Test public void explicitSortAndFiltersKeepTheirExistingBehavior() throws Exception {
        ApiRequest request = request(Map.of("sort", "name", "order", "desc", "name", "visible"));
        new ParseCollectionAttributes().handle(request);
        assertEquals("name", request.getSort().getName());
        assertEquals(SortOrder.DESC, request.getSort().getOrderEnum());
        assertTrue(request.getConditions().containsKey("name"));
        assertTrue(request.getSort().getReverse().toString().contains("order=asc"));
    }

    @Test public void unknownOrUnfilterableSortIsStillIgnored() throws Exception {
        for (String sort : List.of("unknown", "unfilterable")) {
            ApiRequest request = request(Map.of("sort", sort));
            new ParseCollectionAttributes().handle(request);
            assertNull(request.getSort());
        }
    }

    @Test public void invalidOrderStillDefaultsToAscendingForExplicitSort() throws Exception {
        ApiRequest request = request(Map.of("sort", "name", "order", "invalid"));
        new ParseCollectionAttributes().handle(request);
        assertEquals(SortOrder.ASC, request.getSort().getOrderEnum());
    }

    @Test public void lowerThenUpperDateBoundsBothRemainAndConditions() throws Exception {
        assertBothDateBounds(false);
    }

    @Test public void upperThenLowerDateBoundsBothRemainAndConditions() throws Exception {
        assertBothDateBounds(true);
    }

    @Test public void sameModifierMultipleValuesRetainExistingLeafConditions() throws Exception {
        ApiRequest request = request(Map.of("name_eq", List.of("first", "second")));
        new ParseCollectionAttributes().handle(request);
        List<Condition> conditions = request.getConditions().get("name");
        assertEquals(2, conditions.size());
        assertLeafCondition(conditions.get(0), ConditionType.EQ, "first");
        assertLeafCondition(conditions.get(1), ConditionType.EQ, "second");
    }

    @Test public void differentFieldsKeepTheirOwnConditions() throws Exception {
        ApiRequest request = request(Map.of("name_eq", "visible", "state_eq", "active"));
        new ParseCollectionAttributes().handle(request);
        assertEquals(2, request.getConditions().size());
        assertLeafCondition(request.getConditions().get("name").get(0), ConditionType.EQ, "visible");
        assertLeafCondition(request.getConditions().get("state").get(0), ConditionType.EQ, "active");
    }

    @Test public void unsupportedModifiersAndUnknownFieldsRemainIgnored() throws Exception {
        ApiRequest request = request(Map.of("name_eq", "visible", "name_gte", "ignored",
                "created_prefix", "ignored", "created_notamodifier", "ignored", "unknown_eq", "ignored"));
        new ParseCollectionAttributes().handle(request);
        assertEquals(1, request.getConditions().size());
        assertLeafCondition(request.getConditions().get("name").get(0), ConditionType.EQ, "visible");
    }

    @Test public void utcSecondDateUsesUtcRatherThanTheDefaultTimeZone() throws Exception {
        assertUtcDateValue("2026-10-10T00:00:00Z");
    }

    @Test public void utcMillisecondDateIsConvertedRatherThanFallingBackToRawString() throws Exception {
        assertUtcDateValue("2026-10-10T00:00:00.123Z");
    }

    @Test public void invalidDateRetainsTheExistingRawFallback() throws Exception {
        String invalid = "not-a-date";
        ApiRequest request = request(Map.of("created_gte", invalid));
        new ParseCollectionAttributes().handle(request);
        assertLeafCondition(request.getConditions().get("created").get(0), ConditionType.GTE, invalid);
        assertTrue(request.getConditions().get("created").get(0).getValue() instanceof String);
    }

    @Test public void nowBlankAndNullRetainExistingDateContracts() throws Exception {
        long before = System.currentTimeMillis();
        ApiRequest request = request(Map.of("created_eq", List.of("now", "")));
        new ParseCollectionAttributes().handle(request);
        long after = System.currentTimeMillis();
        List<Condition> conditions = request.getConditions().get("created");
        assertEquals(2, conditions.size());
        assertTrue(conditions.get(0).getValue() instanceof Date);
        long now = ((Date) conditions.get(0).getValue()).getTime();
        assertTrue(now >= before && now <= after);
        assertLeafCondition(conditions.get(1), ConditionType.EQ, null);
        assertNull(DateUtils.parse(null));
    }

    @Test public void explicitOffsetDateMatchesUtcInFiltersAndGeneralValidation() throws Exception {
        String input = "2026-10-10T08:00:00.123+08:00";
        Date expected = Date.from(Instant.parse("2026-10-10T00:00:00.123Z"));
        ApiRequest request = request(Map.of("created_eq", input));
        new ParseCollectionAttributes().handle(request);
        assertLeafCondition(request.getConditions().get("created").get(0), ConditionType.EQ, expected);
        Field field = request.getSchemaFactory().getSchema("machine").getResourceFields().get("created");
        assertEquals(FieldType.DATE, field.getTypeEnum());
        assertEquals(expected, ValidationHandler.convertGenericType(field.getName(), input, field.getTypeEnum()));
    }

    @Test public void dateOutputKeepsUtcSecondsAndNullContract() {
        Date input = Date.from(Instant.parse("2026-10-10T00:00:00.123Z"));
        assertEquals("2026-10-10T00:00:00Z", DateUtils.toString(input));
        assertNull(DateUtils.toString(null));
    }

    @Test public void numericBoundsRemainAndLeavesThroughSchemaAndReadValidation() throws Exception {
        for (boolean upperFirst : List.of(false, true)) {
            Map<String, Object> params = new LinkedHashMap<>();
            if (upperFirst) {
                params.put("score_lte", "20");
                params.put("score_gte", "10");
            } else {
                params.put("score_gte", "10");
                params.put("score_lte", "20");
            }
            ApiRequest request = request(params);
            SchemaImpl schema = (SchemaImpl) request.getSchemaFactory().getSchema("machine");
            schema.setCollectionMethods(List.of("GET"));
            Field field = schema.getResourceFields().get("score");
            assertEquals(FieldType.INT, field.getTypeEnum());
            new ParseCollectionAttributes().handle(request);
            ValidationHandler validation = new ValidationHandler();
            validation.init();
            validation.generate(request);
            List<Condition> conditions = request.getConditions().get("score");
            assertEquals(2, conditions.size());
            Condition lower = conditions.stream().filter(c -> c.getConditionType() == ConditionType.GTE)
                    .findFirst().orElseThrow();
            Condition upper = conditions.stream().filter(c -> c.getConditionType() == ConditionType.LTE)
                    .findFirst().orElseThrow();
            // Non-date query values retain their existing raw representation.
            assertLeafCondition(lower, ConditionType.GTE, "10");
            assertLeafCondition(upper, ConditionType.LTE, "20");
            assertEquals(10L, ValidationHandler.convertGenericType(field.getName(), lower.getValue(), field.getTypeEnum()));
            assertEquals(20L, ValidationHandler.convertGenericType(field.getName(), upper.getValue(), field.getTypeEnum()));
        }
    }

    @Test public void dateOutsideDateRangeRetainsInvalidFormatAndRawFallbackContracts() throws Exception {
        String input = "+1000000000-12-31T23:59:59Z";
        assertThrows(ParseException.class, () -> DateUtils.parse(input));
        ClientVisibleException error = assertThrows(ClientVisibleException.class,
                () -> ValidationHandler.convertDate("created", input));
        assertEquals(ValidationErrorCodes.INVALID_DATE_FORMAT, error.getApiError().getCode());
        ApiRequest request = request(Map.of("created_gte", input));
        new ParseCollectionAttributes().handle(request);
        assertLeafCondition(request.getConditions().get("created").get(0), ConditionType.GTE, input);
    }

    private void assertBothDateBounds(boolean upperFirst) throws Exception {
        String lower = "2026-10-10T00:00:00Z";
        String upper = "2026-10-11T00:00:00Z";
        Map<String, Object> params = new LinkedHashMap<>();
        if (upperFirst) {
            params.put("created_lte", upper);
            params.put("created_gte", lower);
        } else {
            params.put("created_gte", lower);
            params.put("created_lte", upper);
        }
        ApiRequest request = request(params);
        new ParseCollectionAttributes().handle(request);
        List<Condition> conditions = request.getConditions().get("created");
        assertNotNull(conditions);
        assertEquals("Both bounds must survive as separate AND leaves", 2, conditions.size());
        Condition lowerCondition = conditions.stream().filter(c -> c.getConditionType() == ConditionType.GTE)
                .findFirst().orElseThrow();
        Condition upperCondition = conditions.stream().filter(c -> c.getConditionType() == ConditionType.LTE)
                .findFirst().orElseThrow();
        assertLeafCondition(lowerCondition, ConditionType.GTE, Date.from(Instant.parse(lower)));
        assertLeafCondition(upperCondition, ConditionType.LTE, Date.from(Instant.parse(upper)));
    }

    private void assertUtcDateValue(String input) throws Exception {
        ApiRequest request = request(Map.of("created_gte", input));
        new ParseCollectionAttributes().handle(request);
        Condition condition = request.getConditions().get("created").get(0);
        assertTrue("DATE filter must produce a Date, not its raw fallback: " + input,
                condition.getValue() instanceof Date);
        assertLeafCondition(condition, ConditionType.GTE, Date.from(Instant.parse(input)));
    }

    private void assertLeafCondition(Condition condition, ConditionType type, Object value) {
        assertEquals(type, condition.getConditionType());
        assertEquals(1, condition.getValues().size());
        assertEquals(value, condition.getValue());
        assertNull(condition.getLeft());
        assertNull(condition.getRight());
    }

    private FieldImpl field(String name, String type) {
        FieldImpl field = new FieldImpl();
        field.setName(name);
        field.setType(type);
        return field;
    }

    private ApiRequest request(Map<String, Object> params) {
        SchemaFactoryImpl factory = new SchemaFactoryImpl();
        SchemaImpl schema = (SchemaImpl) factory.registerSchema("machine");
        schema.getResourceFields().put("name", field("name", "string"));
        schema.getResourceFields().put("state", field("state", "string"));
        schema.getResourceFields().put("created", field("created", "date"));
        schema.getResourceFields().put("score", field("score", "int"));
        schema.getResourceFields().put("unfilterable", new FieldImpl());
        schema.getCollectionFilters().put("name", new Filter(List.of("eq")));
        schema.getCollectionFilters().put("state", new Filter(List.of("eq")));
        schema.getCollectionFilters().put("created", new Filter(List.of("eq", "gte", "lte")));
        schema.getCollectionFilters().put("score", new Filter(List.of("gte", "lte")));
        ApiRequest request = new ApiRequest(null, null);
        request.setSchemaFactory(factory); request.setType("machine"); request.setMethod("GET");
        request.setRequestUrl("http://example.invalid/v1/machines");
        request.setRequestObject(new LinkedHashMap<>(params));
        request.setUrlBuilder(new DefaultUrlBuilder(request, factory));
        ApiContext context = ApiContext.newContext(); context.setApiRequest(request);
        // Filtering string fields does not need an ID formatter.
        return request;
    }
}
