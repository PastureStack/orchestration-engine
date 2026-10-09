package io.github.ibuildthecloud.gdapi.request.handler;

import static org.junit.Assert.*;

import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.impl.SchemaFactoryImpl;
import io.github.ibuildthecloud.gdapi.model.Filter;
import io.github.ibuildthecloud.gdapi.model.Sort.SortOrder;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.url.DefaultUrlBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.After;
import org.junit.Test;

public class ParseCollectionAttributesTest {
    @After public void clearContext() { ApiContext.remove(); }

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

    private ApiRequest request(Map<String, Object> params) {
        SchemaFactoryImpl factory = new SchemaFactoryImpl();
        SchemaImpl schema = (SchemaImpl) factory.registerSchema("machine");
        schema.getResourceFields().put("name", new FieldImpl());
        schema.getResourceFields().put("unfilterable", new FieldImpl());
        schema.getCollectionFilters().put("name", new Filter(List.of("eq")));
        ApiRequest request = new ApiRequest(null, null);
        request.setSchemaFactory(factory); request.setType("machine"); request.setMethod("GET");
        request.setRequestUrl("http://example.invalid/v1/machines");
        request.setRequestObject(new HashMap<>(params));
        request.setUrlBuilder(new DefaultUrlBuilder(request, factory));
        ApiContext context = ApiContext.newContext(); context.setApiRequest(request);
        // Filtering string fields does not need an ID formatter.
        return request;
    }
}
