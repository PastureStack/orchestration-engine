package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.core.model.Credential;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.tables.records.CredentialRecord;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.token.TokenService;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.id.IdentityFormatter;
import io.github.ibuildthecloud.gdapi.model.Action;
import io.github.ibuildthecloud.gdapi.model.impl.SchemaImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ApiKeyDelegationServiceTest {
    @Before public void setup() { ApiContext.newContext().setIdFormatter(new IdentityFormatter()); }
    @After public void cleanup() { ApiContext.remove(); }

    @Test public void restrictedGrantHasNoRollbackClaimsOrKeySecretAndClampsExpiry() {
        Fixture f = new Fixture();
        String token = f.issue(Date.from(f.now.plusSeconds(20)));
        Map<String, Object> claims = f.tokens.get(token);
        assertFalse(claims.containsKey("hostUuid"));
        assertFalse(claims.containsKey("exec"));
        assertEquals(f.now.plusSeconds(20).getEpochSecond(), claims.get("exp"));
        assertFalse(claims.toString().contains("original-key-secret"));
        assertEquals(12, f.service.introspect(token).key().credentialId());
        assertTrue(f.authorizations >= 2);
    }

    @Test public void legacyAndFullWithoutExpiryKeepNonEnforcingClaimsAndOriginalTtl() {
        Fixture f = new Fixture();
        ApiRequest request = f.request();
        ApiKeyCredentialContext.attach(request,new ApiKeyCredentialContext(12,42,"apiKey",0,null));
        request.setAttribute("apiKey.audit.decision","ALLOW");
        String legacy=f.service.token(request,f.payload(),null);
        assertFalse(f.tokens.get(legacy).containsKey(ApiKeyDelegationService.CLAIM));
        assertEquals("h1",f.tokens.get(legacy).get("hostUuid"));
        assertTrue(f.tokens.get(legacy).containsKey("exec"));
        ApiKeyCredentialContext.attach(request,new ApiKeyCredentialContext(12,42,"apiKey",1,
                new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,ApiKeyPolicy.Effect.ALLOW,null,List.of())));
        request.setAttribute("apiKey.audit.decision","ALLOW");
        String full=f.service.token(request,f.payload(),null);
        assertFalse(f.tokens.get(full).containsKey(ApiKeyDelegationService.CLAIM));
        assertEquals("h1",f.tokens.get(full).get("hostUuid"));
        assertEquals(2,f.tokens.size());
    }

    @Test public void handshakeAndLiveChecksRejectRevocationRevisionExpiryAndOwnerLoss() {
        Fixture f = new Fixture(); String token = f.issue(null);
        f.key.setState("inactive"); assertCode("ApiKeyRevoked",()->f.service.introspect(token));
        f.key.setState("active"); f.key.setRemoved(new Date()); assertCode("ApiKeyRevoked",()->f.service.introspect(token));
        f.key.setRemoved(null); f.store(f.policy,4); assertCode("ApiKeyPolicyChanged",()->f.service.introspect(token));
        f.store(f.policy,3); f.ownerAllowed=false; assertCode("OwnerPermissionDenied",()->f.service.introspect(token));
        f.ownerAllowed=true; f.service.clock=Clock.fixed(f.now.plusSeconds(90),ZoneOffset.UTC);
        assertCode("ApiKeyExpired",()->f.service.introspect(token));
    }

    @Test public void exactDockerTargetAndHostAreRevalidated() {
        Fixture f = new Fixture(); String token=f.issue(null);
        f.instance.setExternalId("other-container");assertCode("ApiKeyDelegationInvalid",()->f.service.introspect(token));
        f.instance.setExternalId("docker9");f.host.setUuid("other-host");
        assertCode("ApiKeyDelegationTargetChanged",()->f.service.introspect(token));
    }

    @Test public void signedExpiryMismatchAndMixedLegacyClaimsAreRejected() {
        Fixture f=new Fixture();String token=f.issue(null);
        f.tokens.get(token).put("hostUuid","h1");assertCode("ApiKeyDelegationInvalid",()->f.service.introspect(token));
        f.tokens.get(token).remove("hostUuid");f.tokens.get(token).put("exp",f.now.plusSeconds(200).getEpochSecond());
        assertCode("ApiKeyDelegationInvalid",()->f.service.introspect(token));
    }

    @Test public void scopedPolicyIsCheckedAgainRatherThanTrustingSignedAllow() {
        Fixture f=new Fixture();f.store(Fixture.custom(Set.of("exec","read")),3);String token=f.issue(null);
        f.store(Fixture.custom(Set.of("read")),3);
        assertCode("KeyScopeDenied",()->f.service.introspect(token));
    }

    @Test public void introspectionCommitsOneSafeResponseWithReconstructedPolicy() throws Exception {
        Fixture f=new Fixture();String token=f.issue(null);ByteArrayOutputStream output=new ByteArrayOutputStream();
        ServletOutputStream stream=new ServletOutputStream(){
            @Override public void write(int b){output.write(b);}
            @Override public boolean isReady(){return true;}
            @Override public void setWriteListener(WriteListener listener){}
        };
        HttpServletRequest servlet=proxy(HttpServletRequest.class,(name,args)->null);
        HttpServletResponse response=proxy(HttpServletResponse.class,(name,args)->name.equals("getOutputStream")?stream:null);
        ApiRequest request=new ApiRequest(new ApiServletContext(servlet,response,null),f.schemas);
        request.setType("apiKeyDelegation");request.setId("introspect");request.setMethod("POST");request.setRequestObject(Map.of("token",token));
        ApiContext.getContext().setPolicy(null);
        assertTrue(f.service.handleIntrospection(request));assertTrue(request.isCommitted());
        assertSame(f.owner,ApiContext.getContext().getPolicy());
        Map<String,Object> readback=f.service.jsonMapper.readValue(output.toByteArray());
        assertEquals(true,readback.get("allowed"));assertEquals(ApiKeyDelegationService.digest(token),readback.get("tokenDigest"));
        assertFalse(output.toString().contains(token));
        assertEquals("ALLOW",request.getAttribute("apiKey.audit.decision"));
        request.setRequestObject(Map.of("token",token,"accountId",999));
        assertCode("ApiKeyDelegationInvalid",()->f.service.handleIntrospection(request));
    }

    static void assertCode(String code, org.junit.function.ThrowingRunnable call) {
        assertEquals(code,assertThrows(ClientVisibleException.class,call).getCode());
    }
    interface Call {Object call(String method,Object[] args) throws Throwable;}
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,Call call) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(proxy,method,args)->call.call(method.getName(),args));
    }

    static final class Fixture {
        final Instant now=Instant.parse("2026-10-08T10:00:00Z");
        final ApiKeyPolicyCodec codec=new ApiKeyPolicyCodec();
        final CredentialRecord key=new CredentialRecord();
        final InstanceRecord instance=new InstanceRecord();
        final HostRecord host=new HostRecord();
        final ApiKeyDelegationService service=new ApiKeyDelegationService();
        final Map<String,Map<String,Object>> tokens=new HashMap<>();
        ApiKeyPolicy policy=new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL,ApiKeyPolicy.Effect.ALLOW,now.plusSeconds(90),List.of());
        boolean ownerAllowed=true;
        int authorizations;
        final Policy owner=proxy(Policy.class,(method,args)->switch(method){
            case "getAccountId"->7L;case "getAuthenticatedAsAccountId"->42L;
            case "authorizeObject"->ownerAllowed?args[0]:null;case "isOption"->false;default->null;
        });
        final SchemaImpl schema=new SchemaImpl();
        final SchemaFactory schemas=proxy(SchemaFactory.class,(method,args)->method.equals("getSchema")?schema:null);
        Fixture(){
            schema.setResourceMethods(List.of("GET","POST"));schema.setCollectionMethods(List.of("GET","POST"));
            schema.setResourceActions(Map.of("execute",new Action()));
            key.setId(12L);key.setAccountId(42L);key.setKind("apiKeyRestricted");key.setState("active");key.setSecretValue("original-key-secret");store(policy,3);
            instance.setId(9L);instance.setAccountId(7L);instance.setExternalId("docker9");instance.setUuid("container9");
            host.setId(5L);host.setAccountId(7L);host.setUuid("h1");
            service.clock=Clock.fixed(now,ZoneOffset.UTC);service.jsonMapper=new JacksonJsonMapper();
            service.objectManager=proxy(ObjectManager.class,(method,args)->switch(method){
                case "getSchemaFactory"->schemas;
                case "loadResource"->args[0]==Credential.class?key:"container".equals(args[0])?instance:"host".equals(args[0])?host:null;
                case "mappedChildren"->args[0]==instance && args[1]==Host.class?List.of(host):List.of();default->null;
            });
            service.authenticator=new ApiAuthenticator(){
                @Override public CurrentAuthorization currentAuthorization(long principal,long account,ApiRequest request){
                    authorizations++;if(!ownerAllowed)ApiKeyDelegationService.denied("OwnerPermissionDenied");
                    assertEquals(42,principal);assertEquals(7,account);return new CurrentAuthorization(owner,schemas);
                }
            };
            service.targets=new ApiKeyTargetResolver(){
                @Override public Long parseScopeId(String type,String id){return Long.parseLong(id.replaceAll("^1[a-z]+",""));}
                @Override public ApiKeyPolicyEvaluator.Target resolveObject(String type,Object resource,Policy owner){
                    return ApiKeyPolicyEvaluator.Target.projectResource(type,type.equals("host")?"1h5":"1i9","1a7");
                }
            };
            service.tokenService=proxy(TokenService.class,(method,args)->{
                if(method.equals("generateToken")){
                    Map<String,Object> claims=new HashMap<>((Map<String,Object>)args[0]);claims.put("exp",args.length==1?now.plusSeconds(3600).getEpochSecond():((Date)args[1]).toInstant().getEpochSecond());
                    String ticket="signed-ticket-"+tokens.size();tokens.put(ticket,claims);return ticket;
                }
                if(method.equals("getJsonPayload") || method.equals("getAuditSignaturePayload")){if(!tokens.containsKey(args[0]))throw new IllegalArgumentException("invalid signature");return tokens.get(args[0]);}
                return null;
            });
            ApiContext.getContext().setPolicy(owner);
        }
        void store(ApiKeyPolicy next,long revision){policy=next;key.setData(codec.store(key,next,revision));}
        static ApiKeyPolicy custom(Set<String> operations){return new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM,ApiKeyPolicy.Effect.DENY,null,
                List.of(new ApiKeyPolicy.Rule("allowed",ApiKeyPolicy.Effect.ALLOW,ApiKeyPolicy.Scope.global(),operations)));}
        ApiRequest request(){ApiRequest request=new ApiRequest(null,schemas);request.setType("container");request.setId("1i9");request.setMethod("POST");request.setAction("execute");
            ApiKeyCredentialContext.attach(request,new ApiKeyCredentialContext(12,42,key.getKind(),codec.revision(key),policy));
            request.setAttribute("apiKey.audit.decision","ALLOW");return request;}
        Map<String,Object> payload(){return Map.of("hostUuid","h1","exec",Map.of("Container","docker9","Cmd",List.of("sh")));}
        String issue(Date expiry){return service.token(request(),payload(),expiry);}
    }
}
