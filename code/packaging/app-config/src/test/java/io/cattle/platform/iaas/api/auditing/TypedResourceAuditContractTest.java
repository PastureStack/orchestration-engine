package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.iaas.api.auditing.dao.AuditLogDao;
import io.cattle.platform.iaas.api.auth.change.AiResourceChangeDescriptor;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
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
import java.util.Set;
import java.util.TreeSet;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Composition contract uses the existing app-config dependency closure; no runtime module dependency is added. */
public class TypedResourceAuditContractTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void everyRegisteredTypedDomainHasRealClosedMetadataAuditAllowAndDeny() throws Exception {
        var registry = AiResourceChangeDescriptor.class.getDeclaredField("DESCRIPTORS");
        registry.setAccessible(true); Object value = registry.get(null);
        assertTrue(value instanceof Map<?, ?>);
        Set<String> types = new TreeSet<>();
        for (Object entry : ((Map<?, ?>) value).values()) {
            assertTrue(entry instanceof AiResourceChangeDescriptor);
            types.add(((AiResourceChangeDescriptor) entry).resourceType());
        }
        assertTrue(types.contains("host"));
        assertTrue(types.contains("registry"));
        assertTrue(types.contains("port"));
        assertTrue(types.contains("instanceLink"));
        List<Map<?, ?>> delivered = new ArrayList<>(); AuditServiceImpl owner = owner(delivered);
        ApiRequest request = new ApiRequest(null, null); request.setType("aiResourceChangePlan"); request.setMethod("POST");
        request.setRequestObject(Map.of("name", "PRIVATE-NAME", "description", "PRIVATE-DESCRIPTION",
                "data", Map.of("credentials", "PRIVATE-CREDENTIAL")));
        request.setAttribute("apiKey.audit.keyId", "1c8"); request.setAttribute("apiKey.audit.policyRevision", 3L);
        request.setAttribute("apiKey.audit.verifiedPrincipalAccountId", 42L);
        Policy actor = (Policy) Proxy.newProxyInstance(Policy.class.getClassLoader(), new Class<?>[] {Policy.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getAccountId")) return 77L;
                    if (method.getName().equals("getAuthenticatedAsAccountId")) return 42L;
                    return null;
                });
        String receipt = "aichange-11111111-1111-1111-1111-111111111111";
        for (String type : types) for (boolean allowed : List.of(true, false)) {
            owner.recordResourceChangeDecision(request, actor, "preview", receipt, type, 10L, allowed,
                    allowed ? "ResourceChangePreviewCreated" : "KeyScopeDenied");
            Map<?, ?> event = delivered.getLast();
            assertEquals(type, event.get("targetType")); assertEquals(allowed ? "ALLOW" : "DENY", event.get("decision"));
            assertEquals("admission", event.get("phase")); assertEquals(receipt, event.get("requestId"));
            assertEquals("1c8", event.get("keyId")); assertEquals(3L, ((Number) event.get("policyRevision")).longValue());
            assertFalse(event.toString().contains("PRIVATE")); assertFalse(event.containsKey("requestObject"));
        }
        assertEquals(types.size() * 2, delivered.size());
        int count = delivered.size();
        for (String unknown : List.of("plugin", "Host", "secret", "host/credentials", "storagePool", "storageDriver",
                "Port", "instancelink", "port/credentials", "instanceLink/credentials")) {
            assertThrows(IllegalArgumentException.class, () -> owner.recordResourceChangeDecision(request, actor,
                    "preview", receipt, unknown, 10L, true, "ResourceChangePreviewCreated"));
        }
        assertEquals(count, delivered.size());
    }

    private AuditServiceImpl owner(List<Map<?, ?>> delivered) throws Exception {
        AuditServiceImpl owner = new AuditServiceImpl(); JacksonMapper json = new JacksonMapper(); json.init();
        owner.jsonMapper = json; owner.idFormatter = new DefaultIdFormatter();
        ((DefaultIdFormatter) owner.idFormatter).setSchemaFactory((SchemaFactory) Proxy.newProxyInstance(SchemaFactory.class.getClassLoader(),
                new Class<?>[] {SchemaFactory.class}, (proxy, method, args) -> method.getName().equals("getBaseType") ? args[0] : null));
        var directory = temporary.newFolder().toPath();
        AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        if (acl != null) {
            var principal = directory.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            Files.setOwner(directory, principal);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
                    .setPermissions(AclEntryPermission.values()).setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
        }
        owner.apiKeyOutbox = new ApiKeyAuditOutbox(directory, json);
        owner.auditLogDao = (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(), new Class<?>[] {AuditLogDao.class},
                (proxy, method, args) -> {
                    assertEquals("createApiKeyEvent", method.getName()); delivered.add(new HashMap<>((Map<?, ?>) args[2])); return null;
                });
        return owner;
    }
}
