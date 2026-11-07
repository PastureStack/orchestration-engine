package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.ValidationError;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.model.impl.ValidationErrorImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;

public class AiResourceChangeValidationFieldTest {
    private static final String PRIVATE = "PRIVATE_TOKEN_VALUE_7c691";
    @After public void cleanup() { ApiContext.remove(); }
    private SchemaFactory schemas() {
        SchemaFactory factory = mock(SchemaFactory.class); SchemaImpl schema = new SchemaImpl();
        schema.setId("portRule"); schema.setResourceFields(Map.of("serviceId", new FieldImpl()));
        when(factory.listSchemas()).thenReturn(List.of(schema)); return factory;
    }
    private ClientVisibleException preview(AiResourceChangePlanServiceTest.Fixture f, ClientVisibleException failure, SchemaFactory factory) {
        when(f.domain.prepare(any(), any(), nullable(String.class), anyMap())).thenThrow(failure);
        ApiRequest request = f.request(null, true); request.setId(null); request.setSchemaFactory(factory);
        request.setUrlBuilder(mock(io.github.ibuildthecloud.gdapi.url.UrlBuilder.class)); ApiContext.getContext().setApiRequest(request);
        request.setRequestObject(Map.of("resourceType", "service", "operation", "update_metadata", "resourceId", "1s7",
                "parameters", Map.of("description", PRIVATE)));
        return assertThrows(ClientVisibleException.class, () -> f.service.preview(request));
    }
    private void noEffect(AiResourceChangePlanServiceTest.Fixture f) {
        assertTrue(f.documents.isEmpty()); assertEquals(0, f.claims); assertEquals(0, f.dispatched);
        verify(f.domain, never()).dispatch(any(), any(), nullable(String.class), anyMap(), anyString());
    }
    @Test public void safeNativeValidationFieldRetainsStatusCodeWithoutValuesOrPrivateNativeText() throws Exception {
        for (int status : List.of(422, 400)) {
            var f = new AiResourceChangePlanServiceTest.Fixture();
            ValidationErrorImpl nativeError = new ValidationErrorImpl("InvalidReference", "serviceId", PRIVATE, PRIVATE);
            nativeError.setStatus(status); nativeError.setId(PRIVATE); nativeError.setFields(Map.of("value", PRIVATE));
            var denied = preview(f, new ClientVisibleException(nativeError), schemas());
            assertTrue(denied.getApiError() instanceof ValidationError);
            var safe = (ValidationErrorImpl) denied.getApiError();
            assertEquals(status, safe.getStatus()); assertEquals("InvalidReference", safe.getCode()); assertEquals("serviceId", safe.getFieldName());
            assertNull(safe.getMessage()); assertNull(safe.getDetail()); UUID.fromString(safe.getId()); assertNotEquals(nativeError.getId(), safe.getId());
            String wire = f.json.writeValueAsString(safe);
            assertFalse(wire.contains(PRIVATE)); assertFalse(wire.contains("\"value\""));
            assertEquals(List.of("preview:aiResourceChangePlan:false:InvalidReference"), f.audits);
            assertFalse(f.audits.toString().contains("serviceId")); assertFalse(f.audits.toString().contains(PRIVATE)); noEffect(f);
        }
    }
    @Test public void malformedOversizedAndUnknownValidationPathsKeepOnlySanitizedStatusCode() {
        for (String field : new String[]{null, "", "serviceId.value", "serviceId[0]", "x".repeat(65), "serviceId\n" + PRIVATE,
                "{\"value\":\"" + PRIVATE + "\"}", PRIVATE}) {
            var f = new AiResourceChangePlanServiceTest.Fixture();
            var denied = preview(f, new ClientVisibleException(new ValidationErrorImpl("InvalidReference", field, PRIVATE, PRIVATE)), schemas());
            assertNull(denied.getApiError()); assertEquals(422, denied.getStatus()); assertEquals("InvalidReference", denied.getCode());
            assertEquals("InvalidReference", denied.getMessage()); assertNull(denied.getDetail());
            assertEquals(List.of("preview:aiResourceChangePlan:false:InvalidReference"), f.audits); noEffect(f);
        }
    }
    @Test public void unavailableOrUnboundedSchemaMetadataCannotExposeAValidationPathOrPayload() {
        SchemaFactory absent = mock(SchemaFactory.class), failed = mock(SchemaFactory.class), oversized = schemas();
        when(failed.listSchemas()).thenThrow(new IllegalStateException(PRIVATE));
        when(oversized.listSchemas()).thenReturn(Collections.nCopies(1025, new SchemaImpl()));
        for (SchemaFactory factory : new SchemaFactory[]{null, absent, failed, oversized}) {
            var f = new AiResourceChangePlanServiceTest.Fixture();
            var denied = preview(f, new ClientVisibleException(new ValidationErrorImpl("InvalidReference", "serviceId", PRIVATE, PRIVATE)), factory);
            assertNull(denied.getApiError()); assertEquals(422, denied.getStatus()); assertEquals("InvalidReference", denied.getCode());
            assertEquals("InvalidReference", denied.getMessage()); assertNull(denied.getDetail()); noEffect(f);
        }
    }
    @Test public void failedAuditRemainsFailClosedBeforeAnyValidationFieldCanBeReturned() {
        var f = new AiResourceChangePlanServiceTest.Fixture();
        doThrow(new IllegalStateException(PRIVATE)).when(f.audit)
                .recordResourceChangeDecision(any(), any(), any(), any(), any(), any(), anyBoolean(), any());
        var denied = preview(f, new ClientVisibleException(new ValidationErrorImpl("InvalidReference", "serviceId", PRIVATE, PRIVATE)), schemas());
        assertNull(denied.getApiError()); assertEquals(503, denied.getStatus()); assertEquals("ResourceChangeAuditUnavailable", denied.getCode());
        assertEquals("ResourceChangeAuditUnavailable", denied.getMessage()); assertNull(denied.getDetail()); noEffect(f);
    }
}
