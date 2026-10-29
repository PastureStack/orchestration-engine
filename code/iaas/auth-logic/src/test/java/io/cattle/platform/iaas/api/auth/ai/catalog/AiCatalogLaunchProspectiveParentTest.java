package io.cattle.platform.iaas.api.auth.ai.catalog;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyAuthorizationService;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.validation.ReferenceValidator;
import io.github.ibuildthecloud.gdapi.validation.ValidationHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Test;

public class AiCatalogLaunchProspectiveParentTest {
    @After public void clear() { ApiContext.remove(); }

    @Test public void realRequiredParentRuleBlocksOrdinaryMissingStackButExactNonDispatchingNamedVolumePreviewWorks() throws Exception {
        Fixture f = new Fixture();
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("resources/content/schema/base/volumeTemplate.json"))) root = root.getParent();
        assertNotNull(root);
        Map<String, Object> definition = new JacksonJsonMapper().readValue(Files.readString(root.resolve("resources/content/schema/base/volumeTemplate.json")));
        assertEquals(true, AiCatalogLaunchInput.map(AiCatalogLaunchInput.map(definition.get("resourceFields")).get("stackId")).get("required"));
        Map<String, Object> withoutParent = Map.of("name", "data");
        assertEquals(422, assertThrows(ClientVisibleException.class, () -> f.access.domain(f.request, f.authority, "volumeTemplate", "POST",
                null, null, withoutParent, domain -> { fail("No missing-parent request may dispatch"); return null; })).getStatus());
        f.access.preflightChild(f.request, f.authority, f.plan, "volumeTemplate", Map.of("name", "data", "stackId", "$createdStack"));
        assertTrue(f.volume.getResourceFields().get("stackId").isRequired()); // The shared schema is untouched.
        verify(f.access.keyAccess, times(1)).authorize(any(), same(f.actor)); verifyNoInteractions(f.references, f.access.managers);
    }

    @Test public void requiredFieldsExistingReferencesDestinationAndForgedSymbolsRemainDenied() {
        Fixture f = new Fixture();
        assertEquals(422, assertThrows(ClientVisibleException.class, () -> f.access.preflightChild(f.request, f.authority, f.plan,
                "volumeTemplate", Map.of("stackId", "$createdStack"))).getStatus());
        FieldImpl secret = field("reference[secret]", false); f.volume.getResourceFields().put("secretId", secret);
        assertEquals(422, assertThrows(ClientVisibleException.class, () -> f.access.preflightChild(f.request, f.authority, f.plan,
                "volumeTemplate", Map.of("name", "data", "stackId", "$createdStack", "secretId", "1se999"))).getStatus());
        verify(f.references).getById("secret", "999");
        Map<String, Object> foreign = new LinkedHashMap<>(f.plan); foreign.put("accountId", 6L);
        assertEquals(403, assertThrows(ClientVisibleException.class, () -> f.access.preflightChild(f.request, f.authority, foreign,
                "volumeTemplate", Map.of("name", "data", "stackId", "$createdStack"))).getStatus());
        for (Map<String, Object> body : List.<Map<String, Object>>of(Map.of("name", "data", "stackId", "1st88"),
                Map.of("name", "data", "stackId", "$createdStack", "deferredReference", true)))
            assertEquals(403, assertThrows(ClientVisibleException.class, () -> f.access.preflightChild(f.request, f.authority, f.plan,
                    "volumeTemplate", body)).getStatus());
        verifyNoInteractions(f.access.managers); verify(f.access.keyAccess, never()).authorize(any(), any());
    }

    @Test public void applyCannotUseDeferredReferenceAndStillValidatesTheActualOwnerStack() {
        Fixture f = new Fixture(); Map<String, Object> body = Map.of("name", "data", "stackId", "$createdStack");
        f.access.preflightChild(f.request, f.authority, f.plan, "volumeTemplate", body);
        assertEquals(422, assertThrows(ClientVisibleException.class, () -> f.access.domain(f.request, f.authority, "volumeTemplate", "POST",
                null, null, body, domain -> { fail("A symbolic reference cannot reach the normal apply manager"); return null; })).getStatus());
        StackRecord actual = new StackRecord(); actual.setId(889L); actual.setAccountId(5L); actual.setKind("stack");
        when(f.references.getById("stack", "889")).thenReturn(actual);
        assertEquals("created", f.access.domain(f.request, f.authority, "volumeTemplate", "POST", null, null,
                Map.of("name", "data", "stackId", "1st889"), domain -> {
                    assertEquals(889L, ((Number) AiCatalogLaunchInput.map(domain.getRequestObject()).get("stackId")).longValue()); return "created";
                }));
        f.plan.put("internalStackId", 889L);
        assertEquals(403, assertThrows(ClientVisibleException.class, () -> f.access.preflightChild(f.request, f.authority, f.plan,
                "volumeTemplate", body)).getStatus());
    }

    private static FieldImpl field(String type, boolean required) {
        FieldImpl field = new FieldImpl(); field.setType(type); field.setCreate(true); field.setRequired(required); field.setNullable(false); return field;
    }
    private static final class Fixture {
        final AiCatalogLaunchAccess access = new AiCatalogLaunchAccess(); final Policy actor = mock(Policy.class);
        final SchemaFactory schemas = mock(SchemaFactory.class); final SchemaImpl volume = new SchemaImpl();
        final ReferenceValidator references = mock(ReferenceValidator.class); final ApiRequest request = new ApiRequest(null, schemas);
        final AiCatalogLaunchAccess.Authorization authority;
        final Map<String, Object> plan = new LinkedHashMap<>(Map.of("id", "aicataloglaunch-11111111-1111-1111-1111-111111111111",
                "actorId", 42L, "accountId", 5L));
        Fixture() {
            when(actor.getAccountId()).thenReturn(5L); when(actor.getAuthenticatedAsAccountId()).thenReturn(42L);
            SchemaImpl stack = new SchemaImpl(); stack.setId("stack"); stack.setCollectionMethods(List.of("POST"));
            volume.setId("volumeTemplate"); volume.setCollectionMethods(List.of("POST"));
            volume.getResourceFields().put("name", field("string", true)); volume.getResourceFields().put("stackId", field("reference[stack]", true));
            when(schemas.getSchema("stack")).thenReturn(stack); when(schemas.getSchema("volumeTemplate")).thenReturn(volume);
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            formatter.setTypeMappings(Map.of("account", "a", "stack", "st", "secret", "se")); access.idFormatter = formatter;
            ApiContext.newContext().setApiRequest(request); ApiContext.getContext().setPolicy(actor); ApiContext.getContext().setIdFormatter(formatter);
            access.validation = new ValidationHandler(); access.validation.setSupportedMethods(Set.of("POST", "GET", "PUT", "DELETE"));
            access.validation.setReferenceValidator(references); access.keyAccess = mock(ApiKeyAuthorizationService.class);
            access.managers = mock(ResourceManagerLocator.class); access.graphs = new AiCatalogLaunchGraph(); access.graphs.json = new JacksonJsonMapper();
            authority = new AiCatalogLaunchAccess.Authorization(new ApiAuthenticator.CurrentAuthorization(actor, schemas), null);
        }
    }
}
