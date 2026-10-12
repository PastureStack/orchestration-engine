package io.github.ibuildthecloud.gdapi.request.resource.impl;

import static org.junit.Assert.*;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.Pagination;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.TypeUtils;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class MutationTargetLookupTest {
    ApiRequest request;
    Probe manager;

    @Before public void setup() {
        ApiContext.newContext();
        request = new ApiRequest(null, null);
        request.setType("stack"); request.setId("1st8"); request.setMethod("PUT");
        ApiContext.getContext().setApiRequest(request);
        manager = new Probe();
    }
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void updateDeleteAndActionHaveOnlyAnExactOperandLookup() {
        assertSame(manager.row, manager.update("stack", "1st8", request));
        assertTarget();
        request.setMethod("DELETE");
        assertSame(manager.row, manager.delete("stack", "1st8", request));
        assertTarget();
        request.setMethod("POST"); request.setAction("upgrade");
        assertSame(manager.row, manager.resourceAction("stack", request));
        assertTarget();
    }
    @Test public void publicReadsAndReferenceLookupsDoNotInheritMutationPurpose() {
        manager.update("stack", "1st8", request);
        manager.getById("stack", "1st8", new ListOptions(request));
        assertNull(manager.options.getMutationTarget());
        manager.getById("service", "1s9", new ListOptions());
        assertNull(manager.options.getMutationTarget());
        manager.list("stack", request);
        assertNull(manager.options.getMutationTarget());
    }
    @Test public void requestIdentityTypeIdAndMethodMustStillMatch() {
        var target = ListOptions.forMutationTarget(request, "stack", "1st8").getMutationTarget();
        assertTrue(target.matches(request, "stack", "1st8"));
        assertFalse(target.matches(request, "stack", "1st9"));
        assertFalse(target.matches(request, "service", "1st8"));
        assertFalse(target.matches(request, "stack", 8L));
        var another = new ApiRequest(null, null);
        another.setType("stack"); another.setId("1st8"); another.setMethod("PUT");
        assertFalse(target.matches(another, "stack", "1st8"));
        request.setMethod("GET"); assertFalse(target.matches(request, "stack", "1st8"));
        request.setMethod("PUT"); request.setId("1st9");
        assertFalse(target.matches(request, "stack", "1st8"));
        request.setId("1st8"); request.setType("service");
        assertFalse(target.matches(request, "stack", "1st8"));
    }
    @Test public void actionAndLinkSnapshotsCannotBeReusedForAnotherOperation() {
        request.setMethod("POST"); request.setAction("upgrade");
        var target = ListOptions.forMutationTarget(request, "stack", "1st8").getMutationTarget();
        request.setAction("remove"); assertFalse(target.matches(request, "stack", "1st8"));
        request.setAction("upgrade"); request.setLink("services");
        assertFalse(target.matches(request, "stack", "1st8"));
    }
    @Test public void actionLookupPreservesTheOriginalUnpaginatedOptions() {
        request.setPagination(new Pagination(1));
        manager.update("stack", "1st8", request);
        assertSame(request.getPagination(), manager.options.getPagination());
        request.setMethod("POST"); request.setAction("upgrade");
        manager.resourceAction("stack", request);
        assertTarget();
        assertNull(manager.options.getPagination());
        assertNull(manager.options.getSort());
        assertNull(manager.options.getInclude());
    }
    @Test public void readAndCollectionRequestsCannotClaimMutationLookup() {
        request.setMethod("GET");
        var target = ListOptions.forMutationTarget(request, "stack", "1st8").getMutationTarget();
        assertFalse(target.matches(request, "stack", "1st8"));
        request.setId(null); request.setMethod("POST");
        target = ListOptions.forMutationTarget(request, "stack", null).getMutationTarget();
        assertFalse(target.matches(request, "stack", null));
    }
    @Test public void failingLookupLeavesNoAmbientStateOnTheSameRequest() {
        manager.fail = true;
        assertThrows(IllegalStateException.class, () -> manager.update("stack", "1st8", request));
        manager.fail = false;
        manager.getById("stack", "1st8", new ListOptions(request));
        assertNull(manager.options.getMutationTarget());
    }
    @Test public void clientFieldsCannotPopulateTheInternalMarker() {
        request.setRequestObject(Map.of("mutationTarget", Map.of("type", "stack", "id", "1st8")));
        assertNull(new ListOptions(request).getMutationTarget());
        assertNull(new ListOptions().getMutationTarget());
    }
    private void assertTarget() {
        assertTrue(manager.options.getMutationTarget().matches(request, "stack", "1st8"));
        assertEquals("1st8", manager.criteria.get(TypeUtils.ID_FIELD));
    }

    static class Probe extends AbstractBaseResourceManager {
        @Override public String[] getTypes() { return new String[] {"stack"}; }
        @Override public Class<?>[] getTypeClasses() { return new Class<?>[0]; }
        final Object row = new Object();
        ListOptions options;
        Map<Object, Object> criteria;
        boolean fail;
        @Override protected Object listInternal(SchemaFactory factory, String type, Map<Object, Object> criteria, ListOptions options) {
            this.options = options; this.criteria = criteria;
            if (fail) throw new IllegalStateException("lookup failed");
            return List.of(row);
        }
        @Override protected Object createInternal(String type, ApiRequest request) { return row; }
        @Override protected Object updateInternal(String type, String id, Object object, ApiRequest request) { return object; }
        @Override protected Object deleteInternal(String type, String id, Object object, ApiRequest request) { return object; }
        @Override protected Object getLinkInternal(String type, String id, String link, ApiRequest request) { return row; }
        @Override protected Object resourceActionInternal(Object object, ApiRequest request) { return object; }
        @Override protected Object collectionActionInternal(Object object, ApiRequest request) { return object; }
    }
}
