package io.cattle.platform.iaas.api.auth.ai.logs;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.cattle.platform.api.auth.*;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.docker.api.model.HostAccess;
import io.cattle.platform.iaas.api.auth.ai.AiKeyAuthorization;
import io.cattle.platform.iaas.api.auth.apikey.*;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.junit.*;

public class ContainerLogsOwnerTest {
    static final String UUID="11111111-1111-1111-1111-111111111111", ID="ailogs-22222222-2222-2222-2222-222222222222";
    final AiContainerLogsService service = new AiContainerLogsService();
    final Policy policy=mock(Policy.class); final Schema schema=mock(Schema.class);
    final SchemaFactory schemas=mock(SchemaFactory.class);
    final ObjectManager objects=mock(ObjectManager.class); final ResourceManager manager=mock(ResourceManager.class);
    final InstanceRecord instance=new InstanceRecord(); final CredentialRecord credential=new CredentialRecord();
    final ApiKeyPolicyCodec codec=new ApiKeyPolicyCodec();
    final ApiKeyAuditSink audit=mock(ApiKeyAuditSink.class);
    ApiRequest request; Runnable duringRead=()->{}; java.util.function.Consumer<Map<String,Object>> outputChange=value->{};
    @Before public void setup() throws Exception {
        when(policy.getAuthenticatedAsAccountId()).thenReturn(42L);when(policy.getAccountId()).thenReturn(5L);
        when(policy.authorizeObject(any())).thenAnswer(c->c.getArgument(0));
        when(schemas.getSchema("container")).thenReturn(schema);when(schema.getResourceMethods()).thenReturn(List.of("GET"));
        when(schema.getResourceActions()).thenReturn(Map.of("logs",new Action(null,null)));
        var servlet=mock(ApiServletContext.class);when(servlet.getRequest()).thenReturn(mock(HttpServletRequest.class));
        request=new ApiRequest(servlet,schemas);request.setType("aiResourceView");request.setMethod("POST");
        ApiContext.newContext().setApiRequest(request);ApiContext.getContext().setPolicy(policy);
        var formatter=new io.cattle.platform.api.formatter.DefaultIdFormatter();formatter.setSchemaFactory(schemas);
        formatter.setTypeMappings(Map.of("project","a","container","i","stack","st","apiKey","c"));
        ApiContext.getContext().setIdFormatter(formatter);
        var parser=new ApiKeyTargetResolver();set(parser,"idFormatter",formatter);service.targets=parser;
        instance.setId(7L);instance.setUuid(UUID);instance.setAccountId(5L);instance.setKind("container");instance.setState("running");
        when(objects.loadResource(Instance.class,7L)).thenReturn(instance);
        credential.setId(9L);credential.setAccountId(42L);credential.setKind("apiKeyRestricted");credential.setState("active");
        when(objects.loadResource(Credential.class,9L)).thenReturn(credential);
        var authenticator=mock(ApiAuthenticator.class);when(authenticator.currentAuthorization(eq(42L),eq(5L),any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(policy,schemas));
        var keys=new AiKeyAuthorization();set(keys,"objects",objects);set(keys,"authenticator",authenticator);
        service.keys=keys;service.authenticator=authenticator;service.objects=objects;service.audit=audit;
        service.managers=mock(ResourceManagerLocator.class);when(service.managers.getResourceManagerByType("container")).thenReturn(manager);
        when(manager.resourceAction(eq("container"),any())).thenAnswer(call->{assertTrue(ApiContainerLogsRead.trusted(call.getArgument(1)));return new HostAccess("ws://control.example/v1/logs/","PRIVATE_NATIVE_TICKET");});
        var targets=mock(ApiKeyTargetResolver.class);
        when(targets.resolve(any(),any())).thenReturn(List.of(ApiKeyPolicyEvaluator.Target.stackResource("container","1i7","1a5","1st8")));
        var access=new ApiKeyAuthorizationService();set(access,"targets",targets);set(access,"auditSinks",List.of(audit));
        service.keyAccess=access;
        var json=new JacksonJsonMapper();json.init();
        service.runner=new AiContainerLogsRunner((bytes,deadline)->{
            Map<String,Object> ticket=json.readValue(bytes);duringRead.run();
            Map<String,Object> result=new LinkedHashMap<>(ticket);result.remove("token");result.remove("expiresAtMillis");
            result.put("text","safe\npassword=PRIVATE_VALUE\nPRIVATE_NATIVE_TICKET");result.put("truncated",false);
            result.put("completeness","unknown");result.put("stopReason","eof");outputChange.accept(result);return json.writeValueAsBytes(result);
        });service.runner.json=json;use(Set.of("logs"));
    }
    @After public void cleanup(){ApiContext.remove();}
    @Test public void logsGrantUsesNativeActionAndReturnsOnlyBoundedUntrustedData() {
        var response=service.read(request,input());assertFalse(response.toString().contains("PRIVATE"));
        assertTrue(response.toString().contains("REDACTED"));assertTrue(ApiAuditPayload.omitted(request));
        verify(audit).recordContainerLogsOutcome(eq(request),eq(policy),anyString(),eq(7L),eq("ALLOW"),eq("Completed"));
    }
    @Test public void metadataReadGrantDoesNotGrantLogs(){use(Set.of("read"));deny();verifyNoInteractions(manager);}
    @Test public void cookieSessionUsesRealOwnerBeforeAndAfterReadAndRevocationDiscards() throws Exception {
        var session = new io.cattle.platform.iaas.api.auth.integration.internal.rancher.TokenSessionBoundaryTest();
        try {
            session.setup();request=session.verifiedRequest();request.setSchemaFactory(schemas);
            ApiContext.getContext().setPolicy(policy);service.sessions=session.owner();
            ApiContext.getContext().setIdFormatter(formatter());
            assertTrue(service.read(request,input()).toString().contains("REDACTED"));
            duringRead=session::revoke;deny();
        } finally { session.cleanup(); }
    }
    @Test public void readonlyNativeSchemaAndCrossProjectUuidCannotBeBypassed(){
        when(schema.getResourceActions()).thenReturn(Map.of());deny();verifyNoInteractions(manager);
        when(schema.getResourceActions()).thenReturn(Map.of("logs",new Action(null,null)));instance.setAccountId(6L);deny();
    }
    @Test public void keyRevokeAndRevisionChangeDuringReadDiscardAllContents(){
        duringRead=()->credential.setState("inactive");deny();credential.setState("active");
        duringRead=()->credential.setData(codec.store(credential,new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,ApiKeyPolicy.Effect.ALLOW,null,List.of()),2));deny();
    }
    @Test public void uuidRoleAndOwnerChangeDuringReadDiscardAllContents(){
        duringRead=()->instance.setUuid("33333333-3333-3333-3333-333333333333");deny();instance.setUuid(UUID);
        duringRead=()->when(schema.getResourceActions()).thenReturn(Map.of());deny();
    }
    @Test public void inputAndOutputRemainClosedAndAuditFailureWithholdsContent(){
        var bad=new HashMap<>(input());bad.put("url","PRIVATE_URL");assertEquals(422,assertThrows(ClientVisibleException.class,()->service.read(request,bad)).getStatus());
        doThrow(new IllegalStateException("PRIVATE_AUDIT_FAILURE")).when(audit).recordContainerLogsOutcome(any(),any(),any(),any(),any(),any());
        var e=assertThrows(ClientVisibleException.class,()->service.read(request,input()));assertEquals("ContainerLogsAuditUnavailable",e.getCode());assertFalse(e.toString().contains("PRIVATE"));
    }
    @Test public void nativeCanonicalContainerIdParserRejectsRawCrossTypeAndImpreciseInputs(){
        for(String value:List.of("7","1a7","+7","01i7","9007199254740993")){
            var bad=new HashMap<>(input());bad.put("resourceId",value);assertEquals(422,assertThrows(ClientVisibleException.class,()->service.read(request,bad)).getStatus());
        }
    }
    @Test public void forgedOutputIdentityUnknownFieldsAndUnboundedContentsAreRejected(){
        for (var change : List.<java.util.function.Consumer<Map<String,Object>>>of(
            row->row.put("containerUuid","33333333-3333-3333-3333-333333333333"), row->row.put("actorId",99),
            row->row.put("projectId",6), row->row.put("operation","exec"),row->row.put("token","PRIVATE"),
            row->row.put("text","x".repeat(65537)),row->row.put("text","x\n".repeat(101)),
            row->row.put("completeness","complete"))) {
            outputChange=change;assertEquals("ContainerLogsUnavailable",assertThrows(ClientVisibleException.class,()->service.read(request,input())).getCode());
        }
    }
    @Test public void requestBoundTicketConsumesOnceAndWrongIdentityBurnsIt(){
        var ticket=ticket();assertEquals("logs",ticket.consume(request,42,5,UUID).get("operation"));assertThrows(IllegalStateException.class,()->ticket.consume(request,42,5,UUID));
        var other=ticket();assertThrows(IllegalStateException.class,()->other.consume(new ApiRequest(request),42,5,UUID));
        assertThrows(IllegalStateException.class,()->other.consume(request,42,5,UUID));
        assertThrows(IllegalStateException.class,()->new AiContainerLogsTicket(request,42,5,UUID,ID,100,System.currentTimeMillis()+4000,new HostAccess("ws://control.example/evil","PRIVATE")));
    }
    @Test public void nativeProofRejectsForgeriesCopiesMutationsAndFollow(){
        ApiRequest domain=new ApiRequest(request);domain.setType("container");domain.setId("7");domain.setMethod("POST");domain.setAction("logs");
        domain.setRequestObject(Map.of("lines",100,"follow",false,"timestamps",true,"since",""));
        domain.setAttribute(ApiContainerLogsRead.class.getName(),Boolean.TRUE);assertFalse(ApiContainerLogsRead.trusted(domain));
        ApiContainerLogsRead.bind(domain,System.currentTimeMillis()+4000);assertTrue(ApiContainerLogsRead.trusted(domain));
        assertFalse(ApiContainerLogsRead.trusted(new ApiRequest(domain)));domain.setId("8");assertFalse(ApiContainerLogsRead.trusted(domain));
        assertThrows(IllegalStateException.class,()->ApiContainerLogsRead.expiration(domain));
        domain.setId("7");domain.setRequestObject(Map.of("lines",100,"follow",true,"timestamps",true,"since",""));assertFalse(ApiContainerLogsRead.trusted(domain));
    }
    @Test public void finalBrokerParserIsCanonicalAndRejectsDuplicateTrailingAndNonMapJson() throws Exception {
        var mapper = spy(new JacksonJsonMapper()); mapper.init();
        String body = "{\"text\":\"actual\"}";
        var canonical = new AiContainerLogsRunner((bytes, deadline) -> body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        canonical.json = mapper;
        assertEquals(Map.of("text", "actual"), canonical.read(ticket(), request, 42, 5, UUID));
        verify(mapper, never()).readValue(any(byte[].class));
        for (String invalid : List.of("{\"text\":\"hidden\",\"text\":\"actual\"}", body + "{}", "[]", "null")) {
            var bad = new AiContainerLogsRunner((bytes, deadline) -> invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            bad.json = mapper;
            assertThrows(java.io.IOException.class, () -> bad.read(ticket(), request, 42, 5, UUID));
        }
        assertEquals(Map.of("text", "actual"), canonical.read(ticket(), request, 42, 5, UUID));
    }
    @Test public void brokerIdentityCannotUseFloatingPointOrNumericStringAliases() {
        assertTrue(service.read(request, input()).toString().contains("REDACTED"));
        for (Object invalid : List.of(42.0, "42", 42.5, Double.NaN, Double.POSITIVE_INFINITY,
                new java.math.BigInteger("18446744073709551658"))) {
            outputChange = row -> row.put("actorId", invalid);
            assertEquals("ContainerLogsUnavailable", assertThrows(ClientVisibleException.class,
                    () -> service.read(request, input())).getCode());
        }
    }
    @Test public void expiredBoundNativeProofFailsClosedWhileLegacyRequestKeepsDefaultExpiry() throws Exception {
        ApiRequest domain = new ApiRequest(request);
        domain.setType("container"); domain.setId("7"); domain.setMethod("POST"); domain.setAction("logs");
        domain.setRequestObject(Map.of("lines", 100, "follow", false, "timestamps", true, "since", ""));
        assertNull(ApiContainerLogsRead.expiration(domain));
        ApiContainerLogsRead.bind(domain, System.currentTimeMillis() + 4000);
        Object proof = domain.getAttribute(ApiContainerLogsRead.class.getName());
        var constructor = proof.getClass().getDeclaredConstructors()[0]; constructor.setAccessible(true);
        Object expired = constructor.newInstance(domain, domain.getId(), Map.copyOf((Map<?, ?>) domain.getRequestObject()), 1L);
        domain.setAttribute(ApiContainerLogsRead.class.getName(), expired);
        assertFalse(ApiContainerLogsRead.trusted(domain));
        assertThrows(IllegalStateException.class, () -> ApiContainerLogsRead.expiration(domain));
        assertThrows(IllegalArgumentException.class, () -> ApiContainerLogsRead.bind(domain, 1L));
    }
    private void use(Set<String> operations){
        var policy=new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM,ApiKeyPolicy.Effect.DENY,null,List.of(new ApiKeyPolicy.Rule("logs",ApiKeyPolicy.Effect.ALLOW,ApiKeyPolicy.Scope.stack("1st8"),operations)));
        credential.setData(codec.store(credential,policy,1));ApiKeyCredentialContext.attach(request,new ApiKeyCredentialContext(9,42,"apiKeyRestricted",1,policy));
    }
    private void deny(){var e=assertThrows(ClientVisibleException.class,()->service.read(request,input()));assertEquals(403,e.getStatus());assertEquals("ContainerLogsDenied",e.getCode());}
    private Map<String,Object> input(){return Map.of("resourceType","container","resourceId","1i7","view","container_logs");}
    private io.cattle.platform.api.formatter.DefaultIdFormatter formatter(){
        var f=new io.cattle.platform.api.formatter.DefaultIdFormatter();f.setSchemaFactory(schemas);
        f.setTypeMappings(Map.of("project","a","container","i","stack","st","apiKey","c"));return f;
    }
    private AiContainerLogsTicket ticket(){return new AiContainerLogsTicket(request,42,5,UUID,ID,100,System.currentTimeMillis()+4000,new HostAccess("ws://control.example/v1/logs/","PRIVATE"));}
    static void set(Object o,String name,Object value)throws Exception {var f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
}
