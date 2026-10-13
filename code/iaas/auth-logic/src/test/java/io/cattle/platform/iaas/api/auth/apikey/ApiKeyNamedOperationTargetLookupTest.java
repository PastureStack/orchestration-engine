package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.StackTable.STACK;
import static io.cattle.platform.iaas.api.auth.apikey.ApiKeyMutationTargetLookupTest.*;

import io.cattle.platform.api.link.LinkHandler;
import io.cattle.platform.core.model.tables.records.AccountRecord;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.ListOptions;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.ResourceImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.ResourceManagerRequestHandler;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.validation.ResourceManagerReferenceValidator;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Test;

/** Real validator, manager and SQL-guard dispatch; the JDBC fixture captures SQL without executing a database. */
public class ApiKeyNamedOperationTargetLookupTest {
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void exportOnlyGetReachesTheMatchedLinkWithoutGrantingRead() throws Exception {
        for (String version : List.of("v1", "v2-beta")) {
            for (String type : List.of("stack", "composeProject")) {
                Fixture f = new Fixture(version, type, "GET", null, custom("export"));
                LinkHandler link = registerLink(f, "composeconfig");
                assertSame(f.row, dispatch(f));
                assertEquals("export", f.access.operation);
                assertTrue(f.manager.observed.getMutationTarget().isNamedLink());
                verify(link).link("composeconfig", f.row, f.request);
                assertNull(f.manager.getById(type, "8", new ListOptions(f.request)));
                assertEquals("read", f.access.operation);
                assertNull(f.manager.observed.getMutationTarget());
                assertNull(references(f).getById(type, "8"));
                assertEquals("read", f.access.operation);
                assertTrue(((List<?>) f.manager.list(type, f.request)).isEmpty());
                assertEquals("read", f.access.operation);
            }
        }
    }

    @Test public void getLinkWinsOverAnIgnoredActionSelector() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "GET", "upgrade", custom("export"));
        registerLink(f, "composeconfig");
        assertSame(f.row, dispatch(f));
        assertEquals("export", f.access.operation);
        assertEquals(1, f.access.mutations);
        assertEquals(0, f.manager.mutations);
    }

    @Test public void ordinaryAndUnknownMatchedLinksCannotBorrowExport() throws Exception {
        for (String name : List.of("instances", "futurePluginLink")) {
            Fixture f = new Fixture("v2-beta", "stack", "GET", null, custom("export"));
            LinkHandler link = registerLink(f, name);
            // The inner manager is also guarded when entered without the outer authorization call.
            assertNull(f.manager.getLink("stack", "8", name, f.request));
            assertEquals("read", f.access.operation);
            assertNull(f.access.operand);
            verify(link, never()).link(anyString(), any(), any());
        }
        Fixture f = new Fixture("v2-beta", "stack", "GET", null, custom("export"));
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> f.service.authorize(f.request, f.owner)).getCode());
        f.request.setId(null);
        assertTrue(((List<?>) f.manager.list("stack", f.request)).isEmpty());
        assertEquals("read", f.access.operation);
    }

    @Test public void aNamedOperationStillRequiresAMatchedHandler() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "GET", null, custom("export"));
        f.request.setLink("composeconfig");
        f.manager.setLinkHandlers(List.of()); f.manager.setActionHandlers(List.of()); f.manager.start();
        assertNull(dispatch(f));
        assertTrue(f.sql.isEmpty());
    }

    @Test public void namedLinkDoesNotBorrowAnotherCapabilityOrForeignOperand() throws Exception {
        Fixture wrong = new Fixture("v2-beta", "stack", "GET", null, custom("logs"));
        LinkHandler link = registerLink(wrong, "composeconfig");
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> dispatch(wrong)).getCode());
        assertNull(wrong.manager.getLink("stack", "8", "composeconfig", wrong.request));
        verify(link, never()).link(anyString(), any(), any());
        Fixture foreign = new Fixture("v2-beta", "stack", "GET", null, custom("export"));
        link = registerLink(foreign, "composeconfig"); foreign.request.setId("9");
        assertEquals("KeyScopeDenied", assertThrows(ClientVisibleException.class,
                () -> dispatch(foreign)).getCode());
        assertNull(foreign.manager.getLink("stack", "9", "composeconfig", foreign.request));
        verify(link, never()).link(anyString(), any(), any());
    }

    @Test public void ownerSchemaAndOwnerRowRemainUpperBounds() throws Exception {
        Fixture schema = new Fixture("v2-beta", "stack", "GET", null, custom("export"));
        LinkHandler denied = registerLink(schema, "composeconfig");
        when(schema.schema.getResourceMethods()).thenReturn(List.of("PUT"));
        assertEquals("OwnerPermissionDenied", assertThrows(ClientVisibleException.class,
                () -> dispatch(schema)).getCode());
        verify(denied, never()).link(anyString(), any(), any());
        assertTrue(schema.sql.isEmpty());
        Fixture row = new Fixture("v2-beta", "stack", "GET", null, custom("export"));
        denied = registerLink(row, "composeconfig"); row.row.setAccountId(999L);
        assertNull(dispatch(row));
        verify(denied, never()).link(anyString(), any(), any());
        assertTrue(row.sql.get(0).contains("`account_id` = 5"));
    }

    @Test public void namedLinkDenyExpiryAndClosedStopBeforeTheCallback() throws Exception {
        for (ApiKeyPolicy policy : List.of(
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
                        List.of(rule("allow", ApiKeyPolicy.Effect.ALLOW, "export"),
                                rule("deny", ApiKeyPolicy.Effect.DENY, "export"))),
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, NOW,
                        List.of(rule("allow", ApiKeyPolicy.Effect.ALLOW, "export"))),
                new ApiKeyPolicy(ApiKeyPolicy.Mode.CLOSED, ApiKeyPolicy.Effect.DENY, null, List.of()))) {
            Fixture f = new Fixture("v2-beta", "stack", "GET", null, policy);
            LinkHandler link = registerLink(f, "composeconfig");
            assertThrows(ClientVisibleException.class, () -> dispatch(f));
            if (policy.getMode() == ApiKeyPolicy.Mode.CUSTOM && policy.getExpiresAt() == null) {
                assertNull(f.manager.getLink("stack", "8", "composeconfig", f.request));
            } else {
                assertThrows(ClientVisibleException.class,
                        () -> f.manager.getLink("stack", "8", "composeconfig", f.request));
                assertTrue(f.sql.isEmpty());
            }
            verify(link, never()).link(anyString(), any(), any());
        }
    }

    @Test public void namedMarkerCannotBeReusedForAnotherRequestLinkIdOrSchemaClass() throws Exception {
        Fixture f = new Fixture("v2-beta", "composeProject", "GET", null, custom("export"));
        registerLink(f, "composeconfig");
        f.service.authorize(f.request, f.owner);
        var marker = ListOptions.forNamedLinkTarget(f.request, "composeProject", "8", "composeconfig").getMutationTarget();
        assertNotNull(f.service.constrainMutationTarget(f.request, f.factory, "stack", STACK, marker));
        when(f.factory.getSchemaClass("composeProject")).thenReturn((Class) AccountRecord.class);
        assertEquals(f.service.constrain(f.request, f.factory, "stack", STACK).toString(),
                f.service.constrainMutationTarget(f.request, f.factory, "stack", STACK, marker).toString());
        f.request.setLink("instances");
        assertFalse(marker.matches(f.request, "composeProject", "8"));
        f.request.setLink("composeconfig"); f.request.setId("9");
        assertFalse(marker.matches(f.request, "composeProject", "8"));
        f.request.setId("8");
        assertFalse(marker.matches(new ApiRequest(f.request), "composeProject", "8"));
    }

    @Test public void fullAndLegacyKeepTheirExistingNamedLinkPath() throws Exception {
        for (ApiKeyPolicy policy : new ApiKeyPolicy[] {null,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of())}) {
            Fixture f = new Fixture("v2-beta", "stack", "GET", null, policy);
            LinkHandler link = registerLink(f, "composeconfig");
            assertSame(f.row, dispatch(f));
            assertNull(f.access.condition);
            verify(link).link("composeconfig", f.row, f.request);
        }
    }

    @Test public void actionOnlyGrantPassesAvailabilityAndTheActualPostDispatcher() throws Exception {
        for (String version : List.of("v1", "v2-beta")) {
            for (String action : List.of("upgrade", "logs")) {
                Fixture f = new Fixture(version, "stack", "POST", action, custom(action));
                actionAvailable(f, action);
                assertNotNull(dispatch(f));
                assertEquals("8", f.manager.lookupId);
                assertEquals(action, f.access.operation);
                assertEquals(2, f.access.mutations); // Availability and actual manager operand.
                assertEquals(0, f.access.reads);
                assertEquals(1, f.manager.mutations);
                assertFalse(f.manager.observed.getMutationTarget().isNamedLink());
            }
        }
    }

    @Test public void actionAvailabilityDoesNotMakeOrdinaryInputReferencesReadable() throws Exception {
        Fixture f = new Fixture("v2-beta", "stack", "POST", "upgrade", custom("upgrade"));
        actionAvailable(f, "upgrade");
        SchemaImpl input = new SchemaImpl(); input.setId("upgradeInput");
        FieldImpl reference = new FieldImpl(); reference.setType("reference[stack]"); reference.setCreate(true);
        input.getResourceFields().put("otherStack", reference);
        Action action = f.schema.getResourceActions().get("upgrade");
        when(action.getInput()).thenReturn("upgradeInput");
        when(f.factory.getSchema("upgradeInput")).thenReturn(input);
        f.request.setRequestObject(Map.of("otherStack", "8"));
        f.service.authorize(f.request, f.owner);
        ClientVisibleException denied = assertThrows(ClientVisibleException.class,
                () -> validator(f).generate(f.request));
        assertEquals(ValidationErrorCodes.INVALID_REFERENCE, denied.getApiError().getCode());
        assertEquals(1, f.access.mutations);
        assertEquals(1, f.access.reads);
        assertEquals("read", f.access.operation);
        assertEquals(0, f.manager.mutations);
    }

    @Test public void unavailableOrUnregisteredActionDoesNotReachTheMutation() throws Exception {
        Fixture unavailable = new Fixture("v2-beta", "stack", "POST", "upgrade", custom("upgrade"));
        actionAvailable(unavailable, "upgrade"); unavailable.manager.availableResource = new ResourceImpl();
        ClientVisibleException denied = assertThrows(ClientVisibleException.class, () -> dispatch(unavailable));
        assertEquals(ValidationErrorCodes.ACTION_NOT_AVAILABLE, denied.getApiError().getCode());
        assertEquals(0, unavailable.manager.mutations);
        Fixture unknown = new Fixture("v2-beta", "stack", "POST", "futurePluginAction", custom("upgrade"));
        actionAvailable(unknown, "futurePluginAction");
        assertEquals("UnknownOperation", assertThrows(ClientVisibleException.class, () -> dispatch(unknown)).getCode());
        assertTrue(unknown.sql.isEmpty());
        assertEquals(0, unknown.manager.mutations);
        Fixture wrong = new Fixture("v2-beta", "stack", "POST", "upgrade", custom("export"));
        actionAvailable(wrong, "upgrade");
        assertEquals(404, assertThrows(ClientVisibleException.class, () -> validator(wrong).generate(wrong.request)).getStatus());
        assertEquals("upgrade", wrong.access.operation);
        assertEquals(0, wrong.manager.mutations);
    }

    private static LinkHandler registerLink(Fixture f, String name) throws Exception {
        f.request.setLink(name);
        LinkHandler handler = mock(LinkHandler.class);
        when(handler.getTypes()).thenReturn(new String[] {f.request.getType()});
        when(handler.handles(eq(f.request.getType()), anyString(), eq(name), same(f.request))).thenReturn(true);
        when(handler.link(eq(name), any(), same(f.request))).thenReturn(f.row);
        f.manager.setLinkHandlers(List.of(handler)); f.manager.setActionHandlers(List.of()); f.manager.start();
        return handler;
    }

    private static void actionAvailable(Fixture f, String action) throws Exception {
        when(f.schema.getResourceActions()).thenReturn(Map.of(action, mock(Action.class)));
        ResourceImpl resource = new ResourceImpl();
        resource.getActions().put(action, URI.create("http://localhost/actions/" + action).toURL());
        f.manager.availableResource = resource;
    }

    private static ResourceManagerLocator locator(Fixture f) {
        ResourceManagerLocator locator = mock(ResourceManagerLocator.class);
        when(locator.getResourceManagerByType(anyString())).thenReturn(f.manager);
        when(locator.getResourceManager(same(f.request))).thenReturn(f.manager);
        return locator;
    }

    private static ResourceManagerReferenceValidator references(Fixture f) {
        ResourceManagerReferenceValidator references = new ResourceManagerReferenceValidator();
        references.setLocator(locator(f));
        return references;
    }

    private static ValidationHandler validator(Fixture f) {
        ValidationHandler validator = new ValidationHandler();
        validator.setReferenceValidator(references(f)); validator.init();
        return validator;
    }

    private static Object dispatch(Fixture f) throws Exception {
        f.service.authorize(f.request, f.owner);
        validator(f).generate(f.request);
        ResourceManagerRequestHandler handler = new ResourceManagerRequestHandler();
        handler.setResourceManagerLocator(locator(f)); handler.handle(f.request);
        return f.request.getResponseObject();
    }
}
