package io.cattle.platform.iaas.api.auth.apikey;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.api.formatter.DefaultIdFormatter;
import io.cattle.platform.core.constants.AgentConstants;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.*;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.*;
import io.cattle.platform.engine.context.EngineContext;
import io.cattle.platform.engine.manager.impl.ProcessRecord;
import io.cattle.platform.engine.process.*;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.iaas.api.auth.projects.ProjectAccessService;
import io.cattle.platform.object.ObjectManager;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.model.Schema;
import java.lang.reflect.Field;
import java.time.*;
import java.util.*;
import org.junit.Test;

public class ApiKeyLoadBalancerAgentDependencyTest {
    @Test public void factoryAgentCreateUsesVerifiedInstanceBeforeAgentIdIsWrittenBack() throws Exception {
        try (Fixture f = new Fixture()) {
            assertNull(f.instance.getAgentId()); assertNull(f.agent.getAccountId());
            f.allow("agent", "20", "agent.create", "instance", "3", "instance.create");
        }
        try (Fixture f = new Fixture()) {
            f.key.setKind("apiKey"); f.key.setData(null); f.metadata.put("policyRevision", 0L);
            f.allow("agent", "20", "agent.create", "instance", "3", "instance.create");
            f.allow("account", "30", "account.create", "agent", "20", "agent.create");
            f.allow("credential", "40", "credential.create", "account", "30", "account.create");
        }
        try (Fixture f = new Fixture()) {
            f.processId = null;
            f.allow("agent", "20", "agent.create", "instance", "3", "instance.create");
            f.allow("account", "30", "account.create", "agent", "20", "agent.create");
            f.allow("credential", "40", "credential.create", "account", "30", "account.create");
        }
    }

    @Test public void nativeAgentAccountAndCredentialCreationUseTheSameFactoryRelation() throws Exception {
        try (Fixture f = new Fixture()) {
            f.allow("account", "30", "account.create", "agent", "20", "agent.create");
            f.allow("credential", "40", "credential.create", "account", "30", "account.create");
        }
        try (Fixture f = new Fixture()) {
            f.instance.setData(Map.of("fields", Map.of("labels", Map.of(io.cattle.platform.core.util.SystemLabels.LABEL_AGENT_CREATE, "true", io.cattle.platform.core.util.SystemLabels.LABEL_AGENT_ROLE, "environmentAdmin,agent"))));
            f.agent.setData(Map.of(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID, 5, AgentConstants.DATA_REQUESTED_ROLES, List.of("agent", "environmentAdmin")));
            f.account.setUuid("agentAccount20-environmentAdmin");
            f.account.setData(Map.of(AccountConstants.DATA_AGENT_OWNER_ID, 20, AccountConstants.DATA_ACT_AS_RESOURCE_ADMIN_ACCOUNT, true));
            f.allow("account", "30", "account.create", "agent", "20", "agent.create");
            f.allow("credential", "40", "credential.create", "account", "30", "account.create");
        }
    }

    @Test public void anUnrelatedAgentOrUnverifiedDetachedWorkCannotInheritTheLbGrant() throws Exception {
        for (String invalid : List.of("detached", "unbound-queued", "forged-payload", "foreign-parent", "parent-process", "null-parent-process", "uri", "project", "resource-account", "agent-account", "roles", "instance-owner", "instance-error", "removed-map", "map-removing", "map-owner", "duplicate-map", "stack-owner"))
            try (Fixture f = new Fixture()) {
                switch (invalid) {
                case "uri" -> f.agent.setUri("event:///instanceId=99");
                case "project" -> f.instance.setAccountId(6L);
                case "agent-account" -> f.agent.setAccountId(99L);
                case "resource-account" -> f.agent.setData(Map.of(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID, 6L,
                        AgentConstants.DATA_REQUESTED_ROLES, List.of("environmentAdmin")));
                case "roles" -> f.agent.setData(Map.of(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID, 5L,
                        AgentConstants.DATA_REQUESTED_ROLES, List.of("admin")));
                case "instance-owner" -> when(f.owner.authorizeObject(f.instance)).thenReturn(null);
                case "instance-error" -> f.instance.setState("error");
                case "removed-map" -> f.map.setRemoved(new Date());
                case "map-removing" -> f.map.setState("removing");
                case "map-owner" -> f.map.setAccountId(6L);
                case "duplicate-map" -> when(f.objects.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L, SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(List.of(f.map, f.map));
                case "stack-owner" -> f.stack.setAccountId(6L);
                default -> { }
                }
                assertThrows(invalid, ProcessAuthorizationDeniedException.class, () -> {
                    if (invalid.equals("detached")) f.hook.beforeExecution(new LaunchConfiguration("agent.create", "agent", "20", null, 0, Map.of()), f.metadata);
                    else if (invalid.equals("unbound-queued")) {
                        EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(f.metadata);
                        var frame = engine.pushVerifiedExecution("instance", "3", "instance.create", f.metadata);
                        LaunchConfiguration child = new LaunchConfiguration("agent.create", "agent", "20", null, 0, Map.of());
                        try { ProcessAuthorization.prepare(child, List.of()); }
                        finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
                        f.hook.beforeExecution(new ProcessRecord(child, 101L, "server"), f.metadata);
                    }
                    else if (invalid.equals("forged-payload")) {
                        LaunchConfiguration forged = new LaunchConfiguration("agent.create", "agent", "20", null, 0,
                                Map.of(ProcessAuthorization.VERIFIED_CHILD_KEY, Map.of("parentType", "instance", "parentId", "3", "parentProcess", "instance.create",
                                    "childType", "agent", "childId", "20", "processName", "agent.create", "rootAuthorization", f.metadata)));
                        EngineContext.getEngineContext().pushAuthorization(f.metadata);
                        try { ProcessAuthorization.prepare(forged, List.of()); }
                        finally { EngineContext.getEngineContext().popAuthorization(); }
                        f.hook.beforeExecution(new ProcessRecord(forged, 100L, "server"), f.metadata);
                    }
                    else f.allow("agent", "20", "agent.create", "instance", invalid.equals("foreign-parent") ? "99" : "3", invalid.equals("null-parent-process") ? null : invalid.equals("parent-process") ? "instance.update" : "instance.create");
                });
            }
    }

    @Test public void aCapturedQueuedRetryStillRechecksExpiryRevocationRevisionAndOwner() throws Exception {
        for (String invalid : List.of("expiry", "revoked", "revision", "root-owner", "instance-owner"))
            try (Fixture f = new Fixture()) {
                LaunchConfiguration job = f.allow("agent", "20", "agent.create", "instance", "3", "instance.create");
                ProcessRecord retry = new ProcessRecord(job, 102L, "server");
                assertFalse(ProcessAuthorization.verifiedChild(retry).references().isEmpty());
                assertNull(EngineContext.getEngineContext().currentVerifiedExecution());
                f.hook.beforeExecution(retry, f.metadata);
                String code;
                switch (invalid) {
                case "expiry" -> { f.policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, f.now.minusSeconds(1), List.of()), 1); code = "ApiKeyExpired"; }
                case "revoked" -> { f.key.setState("inactive"); code = "ApiKeyRevoked"; }
                case "revision" -> { f.policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()), 2); code = "ApiKeyPolicyChanged"; }
                case "instance-owner" -> { when(f.owner.authorizeObject(f.instance)).thenReturn(null); code = "OwnerPermissionDenied"; }
                default -> { when(f.owner.authorizeObject(f.service)).thenReturn(null); code = "OwnerPermissionDenied"; }
                }
                assertEquals(code, assertThrows(ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(retry, f.metadata)).getCode());
            }
    }

    @Test public void customLbAuthorityInheritsOnlyTheVerifiedClosureAndExplicitChildDenialsWin() throws Exception {
        for (String denyType : List.of("none", "container", "agent", "account", "credential"))
            try (Fixture f = new Fixture()) {
                List<ApiKeyPolicy.Rule> rules = new ArrayList<>();
                rules.add(new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5"), Set.of("create")));
                if (!denyType.equals("none")) rules.add(new ApiKeyPolicy.Rule("child-deny", ApiKeyPolicy.Effect.DENY,
                        ApiKeyPolicy.Scope.resource(denyType, Map.of("container", "1i3", "agent", "1ag20", "account", "1a30", "credential", "1c40").get(denyType)), Set.of("create")));
                f.policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, rules), 1);
                String type = denyType.equals("account") ? "account" : denyType.equals("credential") ? "credential" : "agent";
                String id = type.equals("account") ? "30" : type.equals("credential") ? "40" : "20";
                String parentType = type.equals("account") ? "agent" : type.equals("credential") ? "account" : "instance";
                String parentId = type.equals("account") ? "20" : type.equals("credential") ? "30" : "3";
                if (denyType.equals("none")) {
                    f.allow("agent", "20", "agent.create", "instance", "3", "instance.create");
                    f.allow("account", "30", "account.create", "agent", "20", "agent.create");
                    f.allow("credential", "40", "credential.create", "account", "30", "account.create");
                }
                else assertThrows(denyType, ProcessAuthorizationDeniedException.class,
                        () -> f.allow(type, id, type + ".create", parentType, parentId, parentType + ".create"));
            }
    }

    @Test public void genericNativeAncestryDoesNotAuthorizeNonLbOrOtherOperations() throws Exception {
        for (String invalid : List.of("ordinary-service", "mismatched-kind", "preview", "link", "wrong-method", "delete", "deactivate"))
            try (Fixture f = new Fixture()) {
                switch (invalid) {
                case "ordinary-service" -> { f.service.setKind("service"); f.metadata.put("targetType", "service"); }
                case "mismatched-kind" -> f.service.setKind("service");
                case "preview" -> f.metadata.put("preview", true);
                case "link" -> f.metadata.put("requestLink", "instances");
                case "wrong-method" -> f.metadata.put("requestMethod", "GET");
                case "delete" -> { f.metadata.put("operation", "delete"); f.metadata.put("requestMethod", "DELETE"); f.metadata.put("requestCollection", false); }
                case "deactivate" -> { f.metadata.put("operation", "update"); f.metadata.put("requestAction", "deactivate"); f.metadata.put("requestCollection", false); }
                default -> { }
                }
                assertEquals(invalid, "OwnerPermissionDenied", assertThrows(ProcessAuthorizationDeniedException.class,
                        () -> f.allow("agent", "20", "agent.create", "instance", "3", "instance.create")).getCode());
            }
        for (String method : List.of("PUT", "POST")) try (Fixture f = new Fixture()) {
            f.metadata.put("operation", "update"); f.metadata.put("requestCollection", false); f.metadata.put("requestMethod", method);
            if (method.equals("POST")) f.metadata.put("requestAction", "activate");
            f.allow("agent", "20", "agent.create", "instance", "3", "instance.create");
        }
    }

    @Test public void staleFactoryUuidsAndForgedAccountOrCredentialRelationsAreRejected() throws Exception {
        for (String invalid : List.of("agent-uuid", "instance-uuid", "stack-uuid", "service-uuid", "map-uuid", "account-uuid", "credential-kind", "credential-account"))
            try (Fixture f = new Fixture()) {
                if (invalid.endsWith("uuid") && !invalid.equals("account-uuid")) {
                    LaunchConfiguration job = f.allow("agent", "20", "agent.create", "instance", "3", "instance.create");
                    switch (invalid) {
                    case "agent-uuid" -> f.agent.setUuid("replacement");
                    case "instance-uuid" -> f.instance.setUuid("replacement");
                    case "stack-uuid" -> f.stack.setUuid("replacement");
                    case "service-uuid" -> f.service.setUuid("replacement");
                    case "map-uuid" -> f.map.setUuid("replacement");
                    default -> { }
                    }
                    assertThrows(ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(new ProcessRecord(job, 101L, "server"), f.metadata));
                } else {
                    if (invalid.equals("account-uuid")) f.account.setUuid("agentAccount99");
                    if (invalid.equals("credential-kind")) f.credential.setKind("apiKey");
                    if (invalid.equals("credential-account")) f.credential.setAccountId(99L);
                    assertThrows(ProcessAuthorizationDeniedException.class, () -> f.allow("credential", "40", "credential.create", "account", "30", "account.create"));
                }
            }
    }

    @Test public void liveRootOwnerKeyExpiryRevocationAndRevisionStillApplyToInternalAgentCreation() throws Exception {
        for (String invalid : List.of("root-owner", "schema", "expiry", "revoked", "revision"))
            try (Fixture f = new Fixture()) {
                switch (invalid) {
                case "root-owner" -> when(f.owner.authorizeObject(f.service)).thenReturn(null);
                case "schema" -> when(f.schema.getCollectionMethods()).thenReturn(List.of());
                case "expiry" -> f.policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, f.now.minusSeconds(1), List.of()), 1);
                case "revoked" -> f.key.setState("inactive");
                case "revision" -> f.policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()), 2);
                default -> { }
                }
                assertThrows(invalid, ProcessAuthorizationDeniedException.class,
                        () -> f.allow("agent", "20", "agent.create", "instance", "3", "instance.create"));
            }
    }

    @Test public void nativeLbUpgradeAndRollbackUseTheirRegisteredOperation() throws Exception {
        for (String action : List.of("upgrade", "rollback")) for (String type : List.of("service", "loadBalancerService")) for (boolean custom : List.of(false, true))
            try (Fixture f = new Fixture()) {
                f.actionRoot(type, "upgrade", action); if (custom) f.scopedAllow("upgrade");
                var registered = ApiKeyOperations.of("POST", action, null, type);
                assertTrue(registered.registered()); assertEquals("upgrade", registered.id());
                f.allow("agent", "20", "agent.create", "instance", "3", "instance.create");
                f.allow("account", "30", "account.create", "agent", "20", "agent.create");
                f.allow("credential", "40", "credential.create", "account", "30", "account.create");
            }
    }

    @Test public void stackActivationUsesOnlyTheExactPersistedManagedLb() throws Exception {
        for (boolean custom : List.of(false, true)) try (Fixture f = new Fixture()) {
            f.stackActivation(); if (custom) f.scopedAllow("update");
            assertEquals("update", ApiKeyOperations.of("POST", "activateservices", null, "stack").id());
            f.allow("agent", "20", "agent.create", "instance", "3", "instance.create");
            f.allow("account", "30", "account.create", "agent", "20", "agent.create");
            f.allow("credential", "40", "credential.create", "account", "30", "account.create");
        }
        for (String invalid : List.of("foreign-stack", "ordinary-service", "project", "unmanaged", "duplicate-map"))
            try (Fixture f = new Fixture()) {
                f.stackActivation();
                switch (invalid) {
                case "foreign-stack" -> f.service.setStackId(99L);
                case "ordinary-service" -> f.service.setKind("service");
                case "project" -> f.service.setAccountId(6L);
                case "unmanaged" -> f.map.setManaged(false);
                case "duplicate-map" -> f.allMaps(List.of(f.map, f.map));
                default -> { }
                }
                assertThrows(invalid, ProcessAuthorizationDeniedException.class,
                    () -> f.allow("agent", "20", "agent.create", "instance", "3", "instance.create"));
            }
    }

    @Test public void nativeLbCleanupCoversTheExactAgentAccountAndCredentialProcesses() throws Exception {
        for (String[] edge : cleanupEdges()) try (Fixture f = new Fixture()) {
            f.cleanup(); f.allow(edge[0], edge[1], edge[2], edge[3], edge[4], edge[5]);
        }
        try (Fixture f = new Fixture()) {
            f.cleanup(); f.key.setKind("apiKey"); f.key.setData(null); f.metadata.put("policyRevision", 0L);
            for (String[] edge : cleanupEdges()) f.allow(edge[0], edge[1], edge[2], edge[3], edge[4], edge[5]);
        }
        try (Fixture f = new Fixture()) {
            f.cleanup(); f.actionRoot("loadBalancerService", "upgrade", "rollback");
            f.map.setManaged(false); f.map.setUpgrade(true);
            f.allow("agent", "20", "agent.deactivate", "instance", "3", "instance.remove");
        }
        try (Fixture f = new Fixture()) {
            f.cleanup(); f.stackActivation();
            f.allow("agent", "20", "agent.deactivate", "instance", "3", "instance.remove");
        }
        try (Fixture f = new Fixture()) {
            f.cleanup();
            f.instance.setData(Map.of("fields", Map.of("labels", Map.of(io.cattle.platform.core.util.SystemLabels.LABEL_AGENT_CREATE, "true", io.cattle.platform.core.util.SystemLabels.LABEL_AGENT_ROLE, "environmentAdmin,agent"))));
            f.agent.setData(Map.of(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID, 5L, AgentConstants.DATA_REQUESTED_ROLES, List.of("agent", "environmentAdmin"),
                    "fields", Map.of(AgentConstants.FIELD_AUTHORIZED_ROLE_ACCOUNTS, List.of(30L))));
            AccountRecord primary = new AccountRecord(); primary.setId(31L); primary.setUuid("agentAccount20"); primary.setKind("agent"); primary.setState("active"); primary.setData(Map.of());
            f.agent.setAccountId(31L); when(f.objects.loadResource(Account.class, 31L)).thenReturn(primary);
            f.account.setUuid("agentAccount20-environmentAdmin"); f.account.setData(Map.of(AccountConstants.DATA_AGENT_OWNER_ID, 20L, AccountConstants.DATA_ACT_AS_RESOURCE_ADMIN_ACCOUNT, true));
            f.allow("account", "30", "account.remove", "agent", "20", "agent.remove");
            var queued = f.allow("credential", "40", "credential.remove", "account", "30", "account.remove");
            f.agent.setData(Map.of(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID, 5L, AgentConstants.DATA_REQUESTED_ROLES, List.of("agent", "environmentAdmin")));
            assertThrows(ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(new ProcessRecord(queued, 102L, "server"), f.metadata));
        }
    }

    @Test public void capturedCleanupAllowsRetiredParentsButRejectsChangedIdentityAndOwnership() throws Exception {
        for (String invalid : List.of("none", "instance-uuid", "agent-uuid", "map-uuid", "root-uuid", "account-uuid", "project", "root-owner", "instance-owner", "missing-map", "source-revived"))
            try (Fixture f = new Fixture()) {
                f.cleanup();
                var job = f.allow("credential", "40", "credential.remove", "account", "30", "account.remove");
                f.instance.setState("removed"); f.instance.setRemoved(new Date());
                f.service.setState("removed"); f.service.setRemoved(new Date());
                f.agent.setState("removed"); f.agent.setRemoved(new Date());
                f.account.setState("removed"); f.account.setRemoved(new Date());
                switch (invalid) {
                case "instance-uuid" -> f.instance.setUuid("replacement");
                case "agent-uuid" -> f.agent.setUuid("replacement");
                case "map-uuid" -> f.map.setUuid("replacement");
                case "root-uuid" -> f.service.setUuid("replacement");
                case "account-uuid" -> f.account.setUuid("agentAccount99");
                case "project" -> f.instance.setAccountId(6L);
                case "root-owner" -> when(f.owner.authorizeObject(f.service)).thenReturn(null);
                case "instance-owner" -> when(f.owner.authorizeObject(f.instance)).thenReturn(null);
                case "missing-map" -> f.allMaps(List.of());
                case "source-revived" -> { f.instance.setState("running"); f.instance.setRemoved(null); }
                default -> { }
                }
                var queued = new ProcessRecord(job, 102L, "server");
                if (invalid.equals("none")) f.hook.beforeExecution(queued, f.metadata);
                else assertThrows(invalid, ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(queued, f.metadata));
            }
    }

    @Test public void cleanupRechecksRevocationExpiryRevisionAndCustomExplicitDenials() throws Exception {
        for (String invalid : List.of("revoked", "expiry", "revision")) try (Fixture f = new Fixture()) {
            f.cleanup(); var job = f.allow("agent", "20", "agent.deactivate", "instance", "3", "instance.remove");
            if (invalid.equals("revoked")) f.key.setState("inactive");
            if (invalid.equals("expiry")) f.policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, f.now.minusSeconds(1), List.of()), 1);
            if (invalid.equals("revision")) f.policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, null, List.of()), 2);
            assertThrows(invalid, ProcessAuthorizationDeniedException.class, () -> f.hook.beforeExecution(new ProcessRecord(job, 102L, "server"), f.metadata));
        }
        for (String type : List.of("none", "container", "agent", "account", "credential")) try (Fixture f = new Fixture()) {
            f.cleanup(); List<ApiKeyPolicy.Rule> rules = new ArrayList<>();
            rules.add(new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5"), Set.of("delete")));
            if (!type.equals("none")) rules.add(new ApiKeyPolicy.Rule("deny", ApiKeyPolicy.Effect.DENY,
                ApiKeyPolicy.Scope.resource(type, Map.of("container", "1i3", "agent", "1ag20", "account", "1a30", "credential", "1c40").get(type)), Set.of("delete")));
            f.policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null, rules), 1);
            String[] edge = type.equals("account") ? cleanupEdges().get(3) : type.equals("credential") ? cleanupEdges().get(6) : cleanupEdges().getFirst();
            if (type.equals("none")) for (String[] item : cleanupEdges()) f.allow(item[0], item[1], item[2], item[3], item[4], item[5]);
            else assertThrows(type, ProcessAuthorizationDeniedException.class, () -> f.allow(edge[0], edge[1], edge[2], edge[3], edge[4], edge[5]));
        }
    }

    @Test public void cleanupCannotBorrowAnUnrelatedOrUnverifiedNativeParent() throws Exception {
        for (String invalid : List.of("parent", "process", "agent-id", "ordinary-service", "kind", "foreign-account", "unbound"))
            try (Fixture f = new Fixture()) {
                f.cleanup();
                if (invalid.equals("ordinary-service")) f.service.setKind("service");
                if (invalid.equals("kind")) f.credential.setKind("apiKey");
                if (invalid.equals("foreign-account")) f.credential.setAccountId(99L);
                assertThrows(invalid, ProcessAuthorizationDeniedException.class, () -> {
                    if (invalid.equals("unbound")) f.hook.beforeExecution(new LaunchConfiguration("agent.remove", "agent", "20", null, 0, Map.of()), f.metadata);
                    else if (invalid.equals("kind") || invalid.equals("foreign-account")) f.allow("credential", "40", "credential.remove", "account", "30", "account.remove");
                    else f.allow("agent", "20", "agent.remove", invalid.equals("parent") ? "host" : "agent", invalid.equals("agent-id") ? "99" : "20", invalid.equals("process") ? "agent.create" : "agent.deactivate");
                });
            }
    }

    private static List<String[]> cleanupEdges() { return List.of(
        new String[]{"agent", "20", "agent.deactivate", "instance", "3", "instance.remove"},
        new String[]{"agent", "20", "agent.remove", "instance", "3", "instance.purge"},
        new String[]{"agent", "20", "agent.remove", "agent", "20", "agent.deactivate"},
        new String[]{"account", "30", "account.deactivate", "agent", "20", "agent.remove"},
        new String[]{"account", "30", "account.remove", "agent", "20", "agent.remove"},
        new String[]{"account", "30", "account.remove", "account", "30", "account.deactivate"},
        new String[]{"credential", "40", "credential.deactivate", "account", "30", "account.remove"},
        new String[]{"credential", "40", "credential.remove", "account", "30", "account.purge"},
        new String[]{"credential", "40", "credential.remove", "credential", "40", "credential.deactivate"}); }

    static class Fixture implements AutoCloseable {
        final ObjectManager objects = mock(ObjectManager.class);
        final Policy owner = mock(Policy.class);
        final ApiKeyProcessAuthorization hook = new ApiKeyProcessAuthorization();
        final ApiKeyTargetResolver targets = new ApiKeyTargetResolver();
        final ServiceRecord service = new ServiceRecord(); final InstanceRecord instance = new InstanceRecord();
        final AgentRecord agent = new AgentRecord(); final AccountRecord account = new AccountRecord(), project = new AccountRecord();
        final CredentialRecord key = new CredentialRecord(), credential = new CredentialRecord();
        final StackRecord stack = new StackRecord(); final ServiceExposeMapRecord map = new ServiceExposeMapRecord();
        final Schema schema = mock(Schema.class); final Instant now = Instant.parse("2026-11-07T00:00:00Z");
        Long processId = 101L;
        final Map<String, Object> metadata = new LinkedHashMap<>(Map.of("keyId", "1c1", "policyRevision", 1L,
                "principalAccountId", 10L, "accountId", 5L, "operation", "create", "targetType", "loadBalancerService", "targetId", "2",
                "requestMethod", "POST", "requestCollection", true, "preview", false));
        Fixture() throws Exception {
            metadata.put("requestId", "aichange-11111111-1111-1111-1111-111111111111");
            hook.objectManager = objects; hook.targets = targets; hook.authenticator = mock(ApiAuthenticator.class);
            hook.clock = Clock.fixed(now, ZoneOffset.UTC);
            inject(targets, "objectManager", objects);
            DefaultIdFormatter formatter = new DefaultIdFormatter(); formatter.setTypeMappings(Map.of("credential", "c", "container", "i", "instance", "i", "agent", "ag", "service", "s", "loadBalancerService", "s", "stack", "st", "account", "a", "project", "a"));
            SchemaFactory formattingSchemas = mock(SchemaFactory.class); when(formattingSchemas.getBaseType(anyString())).thenAnswer(i -> i.getArgument(0)); formatter.setSchemaFactory(formattingSchemas);
            inject(targets, "idFormatter", formatter);
            ProjectAccessService projects = mock(ProjectAccessService.class); inject(targets, "projectAccess", projects);
            service.setId(2L); service.setUuid("lb"); service.setKind("loadBalancerService"); service.setAccountId(5L); service.setStackId(8L); service.setState("activating");
            instance.setId(3L); instance.setUuid("instance"); instance.setKind("container"); instance.setAccountId(5L); instance.setZoneId(1L); instance.setState("creating");
            instance.setData(Map.of("fields", Map.of("labels", Map.of(io.cattle.platform.core.util.SystemLabels.LABEL_AGENT_CREATE, "true", io.cattle.platform.core.util.SystemLabels.LABEL_AGENT_ROLE, "environmentAdmin"))));
            stack.setId(8L); stack.setUuid("stack"); stack.setAccountId(5L); stack.setState("active");
            project.setId(5L); project.setKind("project"); project.setState("active");
            map.setId(7L); map.setUuid("map"); map.setAccountId(5L); map.setServiceId(2L); map.setInstanceId(3L); map.setManaged(true); map.setState("active");
            agent.setId(20L); agent.setUuid("agent"); agent.setUri("event:///instanceId=3"); agent.setZoneId(1L); agent.setManagedConfig(false); agent.setState("registering");
            agent.setData(Map.of(AgentConstants.DATA_AGENT_RESOURCES_ACCOUNT_ID, 5L, AgentConstants.DATA_REQUESTED_ROLES, List.of("environmentAdmin")));
            account.setId(30L); account.setUuid("agentAccount20"); account.setKind("agent"); account.setState("registering");
            account.setData(Map.of(AccountConstants.DATA_ACT_AS_RESOURCE_ADMIN_ACCOUNT, true));
            credential.setId(40L); credential.setUuid("credential"); credential.setAccountId(30L); credential.setKind("agentApiKey"); credential.setState("registering");
            key.setId(1L); key.setAccountId(10L); key.setKind("apiKeyRestricted"); key.setState("active");
            policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.FULL, ApiKeyPolicy.Effect.ALLOW, now.plusSeconds(3600), List.of()), 1);
            when(owner.getAccountId()).thenReturn(5L); when(owner.authorizeObject(any())).thenAnswer(i -> Set.of(service, instance, stack, map).contains(i.getArgument(0)) ? i.getArgument(0) : null);
            when(projects.checkAccess(project, owner)).thenReturn(project);
            when(objects.loadResource(Credential.class, 1L)).thenReturn(key);
            when(objects.loadResource("loadBalancerService", 2L)).thenReturn(service); when(objects.loadResource("service", 2L)).thenReturn(service); when(objects.loadResource(Service.class, 2L)).thenReturn(service);
            when(objects.loadResource("stack", 8L)).thenReturn(stack); when(objects.loadResource(Instance.class, 3L)).thenReturn(instance); when(objects.loadResource(Stack.class, 8L)).thenReturn(stack); when(objects.loadResource(Account.class, 5L)).thenReturn(project);
            when(objects.loadResource("agent", "20")).thenReturn(agent); when(objects.loadResource(Agent.class, 20L)).thenReturn(agent);
            when(objects.loadResource("account", "30")).thenReturn(account); when(objects.loadResource(Account.class, 30L)).thenReturn(account);
            when(objects.loadResource("credential", "40")).thenReturn(credential); when(objects.loadResource(Credential.class, 40L)).thenReturn(credential);
            when(objects.loadResource(ServiceExposeMap.class, 7L)).thenReturn(map); allMaps(List.of(map));
            SchemaFactory schemas = mock(SchemaFactory.class); when(schemas.getSchema("loadBalancerService")).thenReturn(schema); when(schemas.getSchema("service")).thenReturn(schema); when(schemas.getSchema("stack")).thenReturn(schema);
            when(schema.getCollectionMethods()).thenReturn(List.of("POST")); when(schema.getResourceMethods()).thenReturn(List.of("PUT", "DELETE"));
            when(schema.getResourceActions()).thenReturn(Map.of("activate", mock(io.github.ibuildthecloud.gdapi.model.Action.class), "deactivate", mock(io.github.ibuildthecloud.gdapi.model.Action.class), "upgrade", mock(io.github.ibuildthecloud.gdapi.model.Action.class), "rollback", mock(io.github.ibuildthecloud.gdapi.model.Action.class), "activateservices", mock(io.github.ibuildthecloud.gdapi.model.Action.class)));
            when(hook.authenticator.currentAuthorization(eq(10L), eq(5L), any())).thenReturn(new ApiAuthenticator.CurrentAuthorization(owner, schemas));
        }
        void allMaps(List<ServiceExposeMap> rows) {
            when(objects.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L, SERVICE_EXPOSE_MAP.REMOVED, null, SERVICE_EXPOSE_MAP.MANAGED, true)).thenReturn(rows);
            when(objects.find(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, 3L)).thenReturn(rows);
        }
        void scopedAllow(String operation) { policy(new ApiKeyPolicy(ApiKeyPolicy.Mode.CUSTOM, ApiKeyPolicy.Effect.DENY, null,
            List.of(new ApiKeyPolicy.Rule("root", ApiKeyPolicy.Effect.ALLOW, ApiKeyPolicy.Scope.project("1a5"), Set.of(operation)))), 1); }
        void actionRoot(String type, String operation, String action) {
            metadata.put("targetType", type); metadata.put("operation", operation); metadata.put("requestMethod", "POST"); metadata.put("requestAction", action); metadata.put("requestCollection", false);
        }
        void stackActivation() { actionRoot("stack", "update", "activateservices"); metadata.put("targetId", "8"); }
        void cleanup() {
            metadata.put("operation", "delete"); metadata.put("requestMethod", "DELETE"); metadata.put("requestCollection", false); metadata.remove("requestAction");
            instance.setAgentId(20L); instance.setState("removing"); agent.setAccountId(30L); agent.setState("active");
            map.setState("removed"); map.setRemoved(new Date());
        }
        void policy(ApiKeyPolicy policy, long revision) { key.setData(new ApiKeyPolicyCodec().store(key, policy, revision)); }
        LaunchConfiguration allow(String type, String id, String process, String parentType, String parentId, String parentProcess) {
            EngineContext engine = EngineContext.getEngineContext(); engine.pushAuthorization(metadata);
            var frame = engine.pushVerifiedExecution(parentType, parentId, parentProcess, metadata);
            try {
                LaunchConfiguration job = new LaunchConfiguration(process, type, id, null, 0, Map.of());
                ProcessAuthorization.prepare(job, List.of());
                ProcessRecord record = new ProcessRecord(job, processId, "server"); hook.beforeExecution(record, metadata); return record;
            } finally { engine.popVerifiedExecution(frame); engine.popAuthorization(); }
        }
        @Override public void close() { assertNull(EngineContext.getEngineContext().currentVerifiedExecution()); }
    }
    static void inject(Object target, String name, Object value) throws Exception { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
}
