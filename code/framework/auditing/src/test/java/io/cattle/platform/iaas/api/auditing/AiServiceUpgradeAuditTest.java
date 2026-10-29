package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.*;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.auth.ClientToolAuditEvent;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.iaas.api.auditing.dao.AuditLogDao;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class AiServiceUpgradeAuditTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    @Test public void catalogTypedAndVerifiedDelegatedWritesKeepMetadataWithoutPrivateAnswersOrProof() throws Exception {
        List<Map<?, ?>> delivered = new ArrayList<>(); AuditServiceImpl service = service(delivered);
        service.auditLogDao = (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(), new Class<?>[] { AuditLogDao.class },
                (proxy, method, args) -> {
                    assertTrue(List.of("create", "createApiKeyEvent").contains(method.getName()));
                    delivered.add(new HashMap<>((Map<?, ?>) args[2])); return null;
                });
        ApiRequest request = new ApiRequest(null, null); request.setMethod("POST");
        request.setRequestObject(Map.of("environment", Map.of("PASSWORD", "synthetic-human-private"), "proof", "cld1.synthetic-proof"));
        request.setResponseObject(Map.of("dockerCompose", "synthetic-frozen-compose"));
        request.setAttribute("requestStartTime", 1L); request.setAttribute("requestEndTime", 2L);
        for (String type : List.of("aiCatalogStackLaunchOptions", "aiCatalogStackLaunchPlan", "aiCatalogStackLaunchInputs")) {
            request.setType(type); service.logRequest(request, policy());
        }
        assertTrue(delivered.isEmpty());
        request.setType("service"); service.logRequest(request, policy());
        assertTrue(delivered.removeFirst().containsKey("requestObject")); // Unverified body text cannot omit normal audit.
        io.cattle.platform.api.auth.ApiAuditPayload.verifiedCatalogLaunch(request); service.logRequest(request, policy());
        assertFalse(delivered.getFirst().containsKey("requestObject")); assertFalse(delivered.getFirst().containsKey("responseObject"));
        String plan = "aicataloglaunch-11111111-1111-1111-1111-111111111111";
        service.recordCatalogLaunchDecision(request, policy(), "apply", plan, 889L, true, "CatalogLaunchAccepted");
        service.recordCatalogLaunchDecision(request, policy(), "delegate", plan, 889L, false, "KeyScopeDenied");
        assertEquals("ALLOW", delivered.get(1).get("decision")); assertEquals("DENY", delivered.get(2).get("decision"));
        assertEquals(plan, delivered.get(1).get("requestId"));
        for (String value : List.of("synthetic-human-private", "cld1.", "synthetic-frozen-compose", "environment", "PASSWORD"))
            assertFalse(value, delivered.toString().contains(value));
    }
    @Test public void typedChangesAndViewsNeverEnterGenericPayloadAudit() throws Exception {
        List<Map<?, ?>> delivered = new ArrayList<>(); AuditServiceImpl service = service(delivered);
        ApiRequest request = new ApiRequest(null, null); request.setMethod("POST");
        request.setRequestObject(Map.of("parameters", Map.of("command", List.of("private-program-secret")), "snapshot", "private-snapshot"));
        for (String type : List.of("aiResourceChangePlan", "aiResourceView")) {
            request.setType(type); service.logRequest(request, policy());
        }
        assertTrue(delivered.isEmpty());
        String id = "aichange-11111111-1111-1111-1111-111111111111";
        service.recordResourceChangeDecision(request, policy(), "apply", id, "container", 10L, true, "ResourceChangeAccepted");
        service.recordResourceChangeDecision(request, policy(), "apply", id, "container", 10L, false, "KeyScopeDenied");
        assertEquals("ALLOW", delivered.get(0).get("decision")); assertEquals("DENY", delivered.get(1).get("decision"));
        assertEquals("container", delivered.get(0).get("targetType")); assertEquals(id, delivered.get(0).get("requestId"));
        assertFalse(delivered.toString().contains("private-program-secret")); assertFalse(delivered.toString().contains("private-snapshot"));
        assertFalse(delivered.toString().contains("parameters"));
    }
    @Test public void bothAllowAndDenyUseDurableMetadataOnlyAudit() throws Exception {
        List<Map<?, ?>> delivered = new ArrayList<>();
        AuditServiceImpl service = service(delivered);
        Policy actor = policy();
        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(Map.of("environment", Map.of("SECRET", "do-not-record"), "password", "do-not-record"));
        String plan = "aiplan-11111111-1111-1111-1111-111111111111";
        service.recordServiceUpgradeDecision(request, actor, "apply", plan, 10L, true, "UpgradeAccepted");
        service.recordServiceUpgradeDecision(request, actor, "apply", plan, 10L, false, "PermissionDenied");
        assertEquals(2, delivered.size());
        assertEquals("ALLOW", delivered.get(0).get("decision"));
        assertEquals("DENY", delivered.get(1).get("decision"));
        assertEquals(plan, delivered.get(0).get("requestId"));
        assertEquals("admission", delivered.get(0).get("phase"));
        assertFalse(delivered.toString().contains("do-not-record"));
        assertFalse(delivered.toString().contains("environment"));
    }

    @Test public void reportedSuccessNeverBecomesAuthoritativeSuccessOrAuthorization() throws Exception {
        List<Map<?, ?>> delivered = new ArrayList<>(); AuditServiceImpl service = service(delivered);
        String id = "aitool-11111111-1111-1111-1111-111111111111";
        service.recordClientToolEvent(policy(), new ClientToolAuditEvent(id, "get_service", "result", "succeeded", "NONE", id));
        Map<?, ?> event = delivered.getFirst();
        assertEquals("client_reported", event.get("evidenceSource")); assertEquals("REPORTED", event.get("outcome"));
        assertEquals("NOT_EVALUATED", event.get("decision")); assertEquals("succeeded", event.get("clientReportedStatus"));
        assertEquals("1a42", event.get("actor")); assertEquals(id, event.get("clientCorrelationId"));
        assertFalse(event.containsKey("requestObject")); assertFalse(event.containsKey("keyId"));
        assertThrows(IllegalArgumentException.class, () -> service.recordClientToolEvent(policy(),
                new ClientToolAuditEvent(id, "get_service", "result", "succeeded", "secret raw text", id)));
        assertEquals(1, delivered.size());
    }

    @Test public void descriptionDecisionsAndKeyToolReportsRetainOnlyVerifiedMetadata() throws Exception {
        List<Map<?, ?>> delivered = new ArrayList<>(); List<Object[]> envelopes = new ArrayList<>();
        AuditServiceImpl service = service(delivered, envelopes);
        ApiRequest request = new ApiRequest(null, null);
        request.setRequestObject(Map.of("description", "private new text", "expectedDescription", "private old text",
                "keyId", "forged-body-key", "policyRevision", 999));
        request.setType("aiServiceDescriptionUpdate"); request.setMethod("POST");
        service.logRequest(request, policy());
        assertTrue(delivered.isEmpty()); // Metadata admission replaces generic body/response capture.
        String receipt = "aidescription-11111111-1111-1111-1111-111111111111";
        service.recordServiceDescriptionDecision(request, policy(), receipt, 10L, true, "DescriptionUpdateAccepted");
        service.recordServiceDescriptionDecision(request, policy(), receipt, 10L, false, "DescriptionConflict");
        assertEquals("ALLOW", delivered.get(0).get("decision")); assertEquals("DENY", delivered.get(1).get("decision"));
        assertEquals(receipt, delivered.get(0).get("requestId"));
        assertEquals("api.aiServiceDescriptionUpdate.update", envelopes.get(0)[6]);
        assertEquals("Cookie", envelopes.get(0)[7]); assertFalse(delivered.get(0).containsKey("keyId"));
        // The same server-only attributes are populated by ApiKeyCredentialContext.attach
        // after secret verification; neither body nor headers can supply them.
        request.setAttribute("apiKey.audit.keyId", "1a123"); request.setAttribute("apiKey.audit.policyRevision", 4L);
        request.setAttribute("apiKey.audit.verifiedPrincipalAccountId", 42L);
        service.recordServiceDescriptionDecision(request, policy(), receipt, 10L, true, "DescriptionUpdateAccepted");
        String tool = "aitool-11111111-1111-1111-1111-111111111111";
        service.recordClientToolEvent(request, policy(), new ClientToolAuditEvent(tool, "update_service_description", "result", "succeeded", "NONE", tool));
        for (Map<?, ?> row : delivered.subList(2, 4)) {
            assertEquals("1a123", row.get("keyId")); assertEquals(4L, ((Number) row.get("policyRevision")).longValue());
        }
        assertEquals("BasicAuth", envelopes.get(2)[7]); assertEquals("BasicAuth", envelopes.get(3)[7]);
        assertEquals("NOT_EVALUATED", delivered.get(3).get("decision"));
        assertEquals("client_reported", delivered.get(3).get("evidenceSource"));
        assertFalse(delivered.toString().contains("private new text")); assertFalse(delivered.toString().contains("private old text"));
        assertFalse(delivered.toString().contains("expectedDescription")); assertFalse(delivered.toString().contains("forged-body-key"));
    }

    private AuditServiceImpl service(List<Map<?, ?>> delivered) throws Exception {
        return service(delivered, new ArrayList<>());
    }

    private AuditServiceImpl service(List<Map<?, ?>> delivered, List<Object[]> envelopes) throws Exception {
        AuditServiceImpl service = new AuditServiceImpl();
        JacksonMapper json = new JacksonMapper(); json.init();
        service.jsonMapper = json; service.idFormatter = new DefaultIdFormatter();
        ((DefaultIdFormatter) service.idFormatter).setSchemaFactory((SchemaFactory) Proxy.newProxyInstance(
                SchemaFactory.class.getClassLoader(), new Class<?>[] { SchemaFactory.class },
                (proxy, method, args) -> method.getName().equals("getBaseType") ? args[0] : null));
        var directory = temporary.newFolder().toPath();
        // Match the spool's actual private-directory requirement, also on NTFS
        // where administrator-owned inherited Temp ACLs are not a valid fixture.
        AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        if (acl != null) {
            var owner = directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            Files.setOwner(directory, owner);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                    .setPermissions(AclEntryPermission.values()).setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
        }
        service.apiKeyOutbox = new ApiKeyAuditOutbox(directory, json);
        service.auditLogDao = (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(), new Class<?>[] { AuditLogDao.class },
                (proxy, method, args) -> { assertEquals("createApiKeyEvent", method.getName()); delivered.add(new HashMap<>((Map<?, ?>) args[2]));
                        envelopes.add(args.clone()); return null; });
        return service;
    }
    private Policy policy() {
        return (Policy) Proxy.newProxyInstance(Policy.class.getClassLoader(), new Class<?>[] { Policy.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("getAccountId")) return 77L;
                    if (method.getName().equals("getAuthenticatedAsAccountId")) return 42L;
                    return null;
                });
    }
}
