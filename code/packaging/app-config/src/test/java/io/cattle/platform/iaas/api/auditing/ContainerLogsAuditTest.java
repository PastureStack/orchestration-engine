package io.cattle.platform.iaas.api.auditing;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auditing.dao.AuditLogDao;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.util.*;
import java.util.function.Consumer;
import org.junit.Test;
public class ContainerLogsAuditTest {
    @Test public void allowAndDenyPersistOnlyFixedMetadataNeverBodyContents() {
        var service=new AuditServiceImpl();service.idFormatter=new IdentityFormatter();
        service.apiKeyOutbox=mock(ApiKeyAuditOutbox.class);service.auditLogDao=mock(AuditLogDao.class);
        List<Map<String,Object>> persisted=new ArrayList<>();
        doAnswer(call->{Map<String,Object> row=call.getArgument(0);persisted.add(row);((Consumer<Map<String,Object>>)call.getArgument(1)).accept(row);return null;})
            .when(service.apiKeyOutbox).persist(any(),any());
        var policy=mock(Policy.class);when(policy.getAccountId()).thenReturn(5L);when(policy.getAuthenticatedAsAccountId()).thenReturn(42L);
        var request=new ApiRequest(null,null);request.setRequestObject(Map.of("text","PRIVATE_LOG","token","PRIVATE_TOKEN","url","PRIVATE_URL"));
        for (String outcome:List.of("ALLOW","DENY")) service.recordContainerLogsOutcome(request,policy,"ailogs-11111111-1111-1111-1111-111111111111",7L,outcome,"Completed");
        assertEquals(2,persisted.size());assertFalse(persisted.toString().contains("PRIVATE"));
        for(var row:persisted){var data=(Map<?,?>)row.get("data");assertEquals("logs",data.get("operation"));assertEquals(false,data.get("payloadsIncluded"));assertFalse(data.containsKey("text"));}
        verify(service.auditLogDao,times(2)).createApiKeyEvent(any(),any(),any(),any(),any(),any(),any(),any(),anyLong(),any(),any());
    }
    @Test public void arbitraryReasonCannotSmuggleContentsIntoAudit() {
        var service=new AuditServiceImpl();var policy=mock(Policy.class);
        assertThrows(IllegalArgumentException.class,()->service.recordContainerLogsOutcome(new ApiRequest(null,null),policy,
            "ailogs-11111111-1111-1111-1111-111111111111",7L,"DENY","PRIVATE_RAW_ERROR"));
    }
}
