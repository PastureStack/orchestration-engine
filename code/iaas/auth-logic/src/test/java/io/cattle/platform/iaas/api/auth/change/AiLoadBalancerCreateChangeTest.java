package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.engine.process.LaunchConfiguration;
import io.cattle.platform.engine.process.ProcessAuthorizationDeniedException;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyTargetResolver;
import io.cattle.platform.servicediscovery.api.filter.ServiceValidationFilter;
import io.cattle.platform.servicediscovery.api.service.ServiceCreateValidation;
import io.cattle.platform.servicediscovery.api.service.ServiceDeploymentReadiness;
import io.cattle.platform.json.JacksonJsonMapper;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.impl.FieldImpl;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManagerLocator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AiLoadBalancerCreateChangeTest {
    private Object previousImage, previousBridge;
    @Before public void configure() {
        previousImage = ArchaiusUtil.getConfiguration().getProperty("lb.instance.image");
        previousBridge = ArchaiusUtil.getConfiguration().getProperty("api.ai.bridge.enabled");
        ArchaiusUtil.getConfiguration().setProperty("lb.instance.image", "registry.test/lb:1");
        ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", true);
    }
    @After public void cleanup() {
        if (previousImage == null) ArchaiusUtil.getConfiguration().clearProperty("lb.instance.image");
        else ArchaiusUtil.getConfiguration().setProperty("lb.instance.image", previousImage);
        if (previousBridge == null) ArchaiusUtil.getConfiguration().clearProperty("api.ai.bridge.enabled");
        else ArchaiusUtil.getConfiguration().setProperty("api.ai.bridge.enabled", previousBridge);
        ApiContext.remove();
    }

    @Test public void realFrameworkReferenceConversionPreservesLongServiceIdsInFullSubmittedBodyAndReceiptButRejectsUnsafeNumbers() throws Exception {
        var descriptor = AiResourceChangeDescriptor.require("loadBalancerService", "create");
        Map<String, Object> approved = descriptor.body(descriptor.parameters(input(false)));
        var factory = mock(io.github.ibuildthecloud.gdapi.factory.SchemaFactory.class);
        var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(factory);
        formatter.setTypeMappings(Map.of("service", "s", "stack", "st"));
        when(factory.getBaseType(anyString())).thenAnswer(call -> call.getArgument(0));
        ApiContext.newContext().setIdFormatter(formatter);
        String reference = io.cattle.platform.core.addon.PortRule.class.getMethod("getServiceId")
                .getAnnotation(io.github.ibuildthecloud.gdapi.annotation.Field.class).typeString();
        assertEquals("reference[service]", reference);
        Map<String, Map<String, String>> fields = Map.of(
                "loadBalancerService", Map.of("name", "string", "description", "string", "stackId", "reference[stack]",
                        "startOnCreate", "boolean", "scale", "int", "launchConfig", "launchConfig", "lbConfig", "lbConfig"),
                "launchConfig", Map.of("imageUuid", "string", "restartPolicy", "restartPolicy", "ports", "array[string]", "expose", "array[string]"),
                "restartPolicy", Map.of("name", "string", "maximumRetryCount", "int"),
                "lbConfig", Map.of("portRules", "array[portRule]"),
                "portRule", Map.of("serviceId", reference, "protocol", "string", "sourcePort", "int", "targetPort", "int", "priority", "int"));
        // Fixed metadata for the existing create subset; the real framework recursively converts all body values.
        for (var entry : fields.entrySet()) {
            var schema = mock(io.github.ibuildthecloud.gdapi.model.Schema.class);
            Map<String, io.github.ibuildthecloud.gdapi.model.Field> declared = new LinkedHashMap<>();
            for (var field : entry.getValue().entrySet()) {
                FieldImpl value = new FieldImpl(); value.setType(field.getValue()); value.setCreate(true); declared.put(field.getKey(), value);
            }
            when(schema.getResourceFields()).thenReturn(declared); when(schema.getCollectionMethods()).thenReturn(List.of("POST"));
            when(factory.getSchema(entry.getKey())).thenReturn(schema);
        }
        var request = new ApiRequest(null, factory); request.setType("loadBalancerService"); request.setMethod("POST"); request.setRequestObject(approved);
        var nativeValidation = new io.github.ibuildthecloud.gdapi.validation.ValidationHandler();
        nativeValidation.setSupportedMethods(java.util.Set.of("POST"));
        nativeValidation.generate(request);
        Map<String, Object> normalized = (Map<String, Object>) request.getRequestObject();
        Map<?, ?> nativeRule = (Map<?, ?>) ((List<?>) ((Map<?, ?>) normalized.get("lbConfig")).get("portRules")).get(0);
        assertEquals(Long.class, nativeRule.get("serviceId").getClass()); assertEquals(9L, nativeRule.get("serviceId"));
        java.util.function.Function<String, Long> parse = value -> Long.valueOf(value.matches("[0-9]+") ? value : formatter.parseId(value));
        assertTrue(AiLoadBalancerCreateChange.submittedMatches(approved, normalized, parse, parse));
        ServiceRecord persisted = new ServiceRecord(); persisted.setName("edge"); persisted.setKind("loadBalancerService");
        persisted.setStackId(3L); persisted.setData(Map.of("fields", normalized));
        assertTrue(AiLoadBalancerCreateChange.configurationMatches(persisted, approved, 3, "docker:registry.test/lb:1", parse));
        for (Object id : List.of("1s9", "9", 9, 9L, 9.0D, new java.math.BigDecimal("9.000"))) {
            Map<String, Object> actual = copyBody(normalized); firstRule(actual).put("serviceId", id);
            assertTrue(AiLoadBalancerCreateChange.submittedMatches(approved, actual, parse, parse));
        }
        for (Object id : List.of(0L, -9L, 9.5D, Double.NaN, Double.POSITIVE_INFINITY,
                new java.math.BigInteger("18446744073709551625"), new java.math.BigDecimal("9.0000000000000001"), "9.0", "-9")) {
            Map<String, Object> actual = copyBody(normalized); firstRule(actual).put("serviceId", id);
            assertFalse(AiLoadBalancerCreateChange.submittedMatches(approved, actual, parse, parse));
            persisted.setData(Map.of("fields", actual));
            assertFalse(AiLoadBalancerCreateChange.configurationMatches(persisted, approved, 3, "docker:registry.test/lb:1", parse));
        }
        Map<String, Object> raw = input(false), numericRule = new LinkedHashMap<>(rule("1s9", "public", 8080, 80));
        numericRule.put("serviceId", 9L); raw.put("portRules", List.of(numericRule));
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(raw)); // Raw/model-facing subset remains String-only internally.
    }

    private static Map<String, Object> copyBody(Map<String, Object> value) throws Exception {
        var mapper = new JacksonJsonMapper(); return mapper.readValue(mapper.writeValueAsString(value));
    }
    private static Map<String, Object> firstRule(Map<String, Object> body) {
        return (Map<String, Object>) ((List<?>) ((Map<?, ?>) body.get("lbConfig")).get("portRules")).get(0);
    }

    @Test public void exactTcpRulesUseNativeLbConfigAndPublicOrInternalPortBindingsWithoutDroppingOrGuessing() {
        var descriptor = AiResourceChangeDescriptor.require("loadBalancerService", "create");
        assertTrue(descriptor.requiresStackParent()); assertEquals("create", descriptor.keyOperation());
        for (boolean start : List.of(false, true)) {
            Map<String, Object> parameters = input(start);
            var rules = new ArrayList<>((List<Map<String, Object>>) parameters.get("portRules"));
            rules.add(rule("1s10", "public", 8080, 81)); parameters.put("portRules", rules);
            Map<String, Object> body = descriptor.body(descriptor.parameters(parameters));
            assertEquals(start, body.get("startOnCreate")); assertEquals(1, body.get("scale"));
            Map<?, ?> launch = (Map<?, ?>) body.get("launchConfig");
            assertEquals("docker:registry.test/lb:1", launch.get("imageUuid"));
            assertEquals(List.of("8080:8080/tcp"), launch.get("ports")); assertEquals(List.of("9000:9000/tcp"), launch.get("expose"));
            List<?> actual = (List<?>) ((Map<?, ?>) body.get("lbConfig")).get("portRules");
            assertEquals(3, actual.size()); assertEquals(81, ((Map<?, ?>) actual.get(2)).get("targetPort"));
            assertEquals(3, ((Map<?, ?>) actual.get(2)).get("priority")); assertFalse(((Map<?, ?>) actual.get(0)).containsKey("access"));
            var nativeOwner = new ServiceValidationFilter();
            try {
                var mapper = ServiceValidationFilter.class.getDeclaredField("jsonMapper"); mapper.setAccessible(true);
                mapper.set(nativeOwner, new JacksonJsonMapper());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            ApiRequest request = new ApiRequest(null, null); request.setRequestObject(body);
            nativeOwner.validateLbConfig(request, "loadBalancerService");
        }
    }

    @Test public void unsupportedProtocolsCertificatesSelectorsRawConfigurationAndAmbiguousListenersFailClosed() {
        var descriptor = AiResourceChangeDescriptor.require("loadBalancerService", "create");
        for (String field : List.of("image", "launchConfig", "lbConfig", "scale", "certificateIds", "url")) {
            Map<String, Object> value = input(true); value.put(field, "PRIVATE");
            assertThrows(ClientVisibleException.class, () -> descriptor.parameters(value));
        }
        for (String field : List.of("serviceRef", "selector", "sourceIp", "hostname", "path", "backendName", "weight", "region", "environment")) {
            Map<String, Object> value = input(true), row = new LinkedHashMap<>(rule("1s9", "public", 8080, 80));
            row.put(field, "PRIVATE"); value.put("portRules", List.of(row));
            assertThrows(ClientVisibleException.class, () -> descriptor.parameters(value));
        }
        for (Object protocol : List.of("https", "http", "tls", "sni", "udp", "TCP")) {
            Map<String, Object> value = input(true), row = new LinkedHashMap<>(rule("1s9", "public", 8080, 80));
            row.put("protocol", protocol); value.put("portRules", List.of(row));
            assertThrows(ClientVisibleException.class, () -> descriptor.parameters(value));
        }
        for (Object rules : List.of(List.of(), List.of(rule("1s9", "public", 42, 80)),
                List.of(rule("1s9", "public", 80, 80), rule("1s9", "internal", 80, 81)),
                java.util.Collections.nCopies(17, rule("1s9", "public", 80, 80)))) {
            Map<String, Object> value = input(false); value.put("portRules", rules);
            assertThrows(ClientVisibleException.class, () -> descriptor.parameters(value));
        }
        Map<String, Object> missingStart = input(false); missingStart.remove("startOnCreate");
        assertThrows(ClientVisibleException.class, () -> descriptor.parameters(missingStart));
    }

    @Test public void freshNativeTargetAndStackReadsPreserveRoleKeyRevisionAndCrossProjectKindBoundariesBeforeCreate() throws Exception {
        Fixture f = new Fixture(); var descriptor = AiResourceChangeDescriptor.require("loadBalancerService", "create");
        var parameters = descriptor.parameters(input(true));
        var parentSnapshot = f.domain.loadBalancerCreateParentSnapshot(f.domain.parent(f.request, descriptor, parameters));
        assertEquals(parentSnapshot, new JacksonJsonMapper().readValue(new JacksonJsonMapper().writeValueAsString(parentSnapshot)));
        assertFalse(parentSnapshot.containsKey("data")); assertFalse(parentSnapshot.containsKey("childServices"));
        var body = f.domain.prepare(f.request, descriptor, null, parameters);
        assertEquals(true, body.get("startOnCreate")); verify(f.validation, atLeast(3)).generate(any());
        verify(f.nativeOwner, never()).create(anyString(), any());
        Map<String, Object> bound = Map.of("referenceSnapshot", f.domain.loadBalancerCreateReferences(f.request, descriptor, parameters));
        f.backend.setUuid("changed");
        assertEquals("ResourceChangePlanStale", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor, null, parameters, bound)).getCode());
        f.backend.setUuid("backend"); f.backend.setAccountId(88L);
        assertEquals("NotFound", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor, null, parameters)).getCode());
        f.backend.setAccountId(77L); f.backend.setKind("loadBalancerService");
        assertEquals("NotFound", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor, null, parameters)).getCode());
        f.backend.setKind("service");
        when(f.http.getHeader(io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("2");
        assertEquals("ApiKeyPolicyChanged", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor, null, parameters)).getCode());
        when(f.http.getHeader(io.cattle.platform.iaas.api.auth.apikey.ApiKeyAiBridgeGuard.EXPECTED_REVISION_HEADER)).thenReturn("3");
        when(f.schema.getCollectionMethods()).thenReturn(List.of("GET"));
        assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor, null, parameters)).getCode());
        when(f.schema.getCollectionMethods()).thenReturn(List.of("POST", "GET"));
        when(f.schema.getResourceMethods()).thenReturn(List.of("PUT"));
        assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                () -> f.domain.prepare(f.request, descriptor, null, parameters)).getCode());
    }

    @Test public void approvedTcpBodyCannotBeSilentlyStrippedOrRewrittenByTheNativeSchema() throws Exception {
        var descriptor = AiResourceChangeDescriptor.require("loadBalancerService", "create");
        for (String mode : List.of("lbConfig", "ports", "expose", "start", "target", "normalized")) {
            Fixture f = new Fixture();
            doAnswer(call -> {
                ApiRequest request = call.getArgument(0);
                if (!"loadBalancerService".equals(request.getType()) || !"POST".equals(request.getMethod())) return null;
                Map<String, Object> actual = new JacksonJsonMapper().readValue(new JacksonJsonMapper().writeValueAsString(request.getRequestObject()));
                Map<String, Object> launch = (Map<String, Object>) actual.get("launchConfig");
                if ("lbConfig".equals(mode)) actual.remove("lbConfig");
                if ("ports".equals(mode) || "expose".equals(mode)) launch.remove(mode);
                if ("start".equals(mode)) actual.put("startOnCreate", false);
                if ("target".equals(mode))
                    ((Map<String, Object>) ((List<?>) ((Map<?, ?>) actual.get("lbConfig")).get("portRules")).get(0)).put("serviceId", "1s10");
                if ("normalized".equals(mode)) {
                    actual.put("stackId", "3"); actual.put("scale", 1L);
                    launch.put("networkMode", "managed"); launch.put("restartPolicy", Map.of("name", "always", "maximumRetryCount", 0L));
                    for (Object item : (List<?>) ((Map<?, ?>) actual.get("lbConfig")).get("portRules")) {
                        Map<String, Object> row = (Map<String, Object>) item; row.put("serviceId", "9"); row.put("hostname", null);
                    }
                }
                request.setRequestObject(actual); return null;
            }).when(f.validation).generate(any());
            if ("normalized".equals(mode)) assertEquals("managed",
                     ((Map<?, ?>) f.domain.prepare(f.request, descriptor, null, input(true)).get("launchConfig")).get("networkMode"));
            else assertEquals("PermissionDenied", assertThrows(ClientVisibleException.class,
                    () -> f.domain.prepare(f.request, descriptor, null, input(true))).getCode());
            verify(f.nativeOwner, never()).create(anyString(), any());
        }
    }

    @Test public void humanApprovalAndDurableClaimDispatchExactlyOneNativeCreateAndKeepUnknownOperationsNonReplayable() {
        AiResourceChangeDescriptor.require("loadBalancerService", "create");
        var f = new AiResourceChangePlanServiceTest.Fixture(); f.target.setUuid("parent");
        when(f.domain.loadBalancerCreateParentSnapshot(any())).thenReturn(Map.of("id", 3, "uuid", "parent", "accountId", 77,
                "kind", "stack", "name", "Blue", "state", "active", "archived", false));
        ServiceRecord created = new ServiceRecord(); created.setId(91L); created.setUuid("created-lb");
        doAnswer(call -> { f.dispatched++; return created; }).when(f.domain).dispatch(any(), any(), nullable(String.class), anyMap(), anyString());
        when(f.domain.loadBalancerCreateReferences(any(), any(), anyMap())).thenReturn(Map.of("targets", List.of(Map.of("id", 9))));
        ApiRequest preview = f.request(null, true);
        preview.setRequestObject(Map.of("resourceType", "loadBalancerService", "operation", "create", "parameters", input(true)));
        f.id = (String) f.service.preview(preview).get("id");
        assertEquals("ResourceChangeNeedsConfirmation", assertThrows(ClientVisibleException.class,
                () -> f.service.action(f.id, f.request("apply", true))).getCode()); assertEquals(0, f.dispatched);
        assertEquals("AiKeyConfirmationForbidden", assertThrows(ClientVisibleException.class,
                () -> f.service.action(f.id, f.request("confirm", true))).getCode());
        f.service.action(f.id, f.request("confirm", false));
        assertEquals("accepted", f.service.action(f.id, f.request("apply", true)).get("status"));
        f.service.action(f.id, f.request("apply", true)); assertEquals(1, f.dispatched); assertEquals(1, f.claims);
        f.documents.get(f.id).put("status", "unknown"); f.documents.get(f.id).put("dispatchAcknowledged", false);
        assertEquals("unknown", f.service.action(f.id, f.request("apply", true)).get("status")); assertEquals(1, f.dispatched);
        assertEquals("created-lb", f.documents.get(f.id).get("createdResourceUuid"));
        assertFalse(Boolean.TRUE.equals(f.documents.get(f.id).get("verifiedResult")));
    }

    @Test public void actualCreatedConfigurationAndNativeReadinessAreRequiredInsteadOfCreate202OrInactiveCoincidence() {
        Fixture f = new Fixture(); var descriptor = AiResourceChangeDescriptor.require("loadBalancerService", "create");
        for (boolean start : List.of(false, true)) {
            Map<String, Object> parameters = descriptor.parameters(input(start)), body = descriptor.body(parameters);
            ServiceRecord created = new ServiceRecord(); created.setKind("loadBalancerService"); created.setName("edge");
            created.setStackId(3L); created.setState(start ? "active" : "inactive");
            Map<String, Object> fields = new LinkedHashMap<>(body); created.setData(Map.of("fields", fields));
            Map<String, Object> before = Map.of("loadBalancerCreateBody", body);
            when(f.domain.deploymentReadiness.isReady(created)).thenReturn(false);
            assertEquals(!start, f.domain.complete(descriptor, created, parameters, before));
            when(f.domain.deploymentReadiness.isReady(created)).thenReturn(true);
            assertTrue(f.domain.complete(descriptor, created, parameters, before));
            Map<String, Object> config = new LinkedHashMap<>((Map<String, Object>) fields.get("lbConfig"));
            config.put("certificateIds", List.of("PRIVATE")); fields.put("lbConfig", config);
            assertFalse(f.domain.complete(descriptor, created, parameters, before));
            fields.put("lbConfig", body.get("lbConfig")); created.setStackId(4L);
            assertFalse(f.domain.complete(descriptor, created, parameters, before));
        }
    }

    @Test public void privateApprovedBodyIsCheckedBeforeCookieCreateWithoutRejectingNativeJsonNumberNormalization() throws Exception {
        var descriptor = AiResourceChangeDescriptor.require("loadBalancerService", "create");
        var observer = new AiResourceChangeProcessObserver(); observer.store = mock(AiResourceChangePlanStore.class);
        observer.store.json = new JacksonJsonMapper(); String id = "aichange-00000000-0000-4000-8000-000000000001";
        Map<String, Object> body = descriptor.body(descriptor.parameters(input(true)));
        Map<String, Object> approvedBody = observer.store.json.readValue(observer.store.json.writeValueAsString(body));
        when(observer.store.load(id)).thenReturn(Map.of("operationId", id, "status", "dispatching", "resourceType", "loadBalancerService",
                "operation", "create", "body", approvedBody));
        Map<String, Object> actual = observer.store.json.readValue(observer.store.json.writeValueAsString(body)); actual.put("scale", 1L);
        ApiRequest request = new ApiRequest(null, null); request.setType("loadBalancerService"); request.setMethod("POST"); request.setRequestObject(actual);
        int[] calls = {0}; assertEquals("created", observer.dispatch(request, id, () -> { calls[0]++; return "created"; }));
        ((Map<String, Object>) actual.get("launchConfig")).put("imageUuid", "docker:changed:1");
        assertThrows(IllegalStateException.class, () -> observer.dispatch(request, id, () -> { calls[0]++; return "unapproved"; }));
        assertEquals(1, calls[0]);
    }

    @Test public void ordinaryNativeCreatesRemainUnmodifiedWhileTypedCreateWithoutPrivateBindingCannotEnterTheQueue() throws Exception {
        var observer = new AiResourceChangeProcessObserver(); observer.store = mock(AiResourceChangePlanStore.class);
        var config = new LaunchConfiguration("service.create", "service", "91", 77L, 0, Map.of());
        Map<String, Object> metadata = new LinkedHashMap<>(Map.of("requestId", "ordinary-uuid", "targetType", "loadBalancerService",
                "requestMethod", "POST", "requestCollection", true));
        observer.beforeExecution(config, metadata); verifyNoInteractions(observer.store);
        metadata.put("requestId", "aichange-00000000-0000-4000-8000-000000000001");
        assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(config, metadata));
        var descriptor = AiResourceChangeDescriptor.require("loadBalancerService", "create");
        try (var connection = java.sql.DriverManager.getConnection("jdbc:h2:mem:tcp-create-" + java.util.UUID.randomUUID() + ";DATABASE_TO_LOWER=TRUE")) {
            connection.createStatement().execute("CREATE TABLE ai_resource_change_plan(id VARCHAR(64) PRIMARY KEY,state VARCHAR(32),"
                    + "document CLOB,actor_id BIGINT,expires_at BIGINT,execution_binding CLOB,failure_code VARCHAR(64))");
            var store = new AiResourceChangePlanStore(); store.json = new JacksonJsonMapper();
            store.setConfiguration(new org.jooq.impl.DefaultConfiguration().set(connection).set(org.jooq.SQLDialect.H2));
            observer.store = store; observer.objects = mock(io.cattle.platform.object.ObjectManager.class);
            observer.targets = mock(ApiKeyTargetResolver.class); when(observer.targets.parseScopeId("credential", "1c7")).thenReturn(7L);
            when(observer.targets.parseScopeId("service", "1s9")).thenReturn(9L);
            observer.serviceCreateValidation = mock(ServiceCreateValidation.class);
            when(observer.serviceCreateValidation.imageUuid(anyString())).thenAnswer(call -> call.getArgument(0));
            var root = new ServiceRecord(); root.setId(91L); root.setAccountId(77L); root.setStackId(3L);
            root.setUuid("created"); root.setKind("loadBalancerService"); root.setState("requested");
            var backend = new ServiceRecord(); backend.setId(9L); backend.setAccountId(77L); backend.setStackId(3L);
            backend.setUuid("backend"); backend.setKind("service"); backend.setState("active");
            var stack = new StackRecord(); stack.setId(3L); stack.setAccountId(77L); stack.setUuid("stack"); stack.setKind("stack"); stack.setState("active");
            when(observer.objects.loadResource("service", 91L)).thenReturn(root);
            when(observer.objects.loadResource("service", "91")).thenReturn(root);
            when(observer.objects.loadResource(Service.class, 9L)).thenReturn(backend);
            when(observer.objects.loadResource(Stack.class, 3L)).thenReturn(stack);
            String id = (String) metadata.get("requestId"); Map<String, Object> parameters = descriptor.parameters(input(false));
            Map<String, Object> body = descriptor.body(parameters), plan = new LinkedHashMap<>();
            root.setName("edge"); root.setData(Map.of("fields", body));
            plan.putAll(Map.of("id", id, "actorId", 42L, "accountId", 77L, "credentialId", 7L, "credentialRevision", 3L,
                    "credentialKind", "apiKey", "resourceType", "loadBalancerService", "operation", "create", "status", "preview"));
            plan.put("body", body); plan.put("parameters", parameters); plan.put("snapshot", Map.of()); plan.put("internalResourceId", null);
            plan.put("parentSnapshot", Map.of("id", 3, "uuid", "stack", "accountId", 77, "kind", "stack"));
            plan.put("referenceSnapshot", Map.of("targets", List.of(Map.of("id", 9, "uuid", "backend", "accountId", 77,
                    "stackId", 3, "stackUuid", "stack", "kind", "service"))));
            plan.put("expiresAt", "2030-01-01T00:00:00Z"); plan.put("confirmationState", "approved"); store.insert(plan);
            plan.put("status", "dispatching"); plan.put("operationId", id); assertTrue(store.claim(plan));
            var owner = mock(io.cattle.platform.api.auth.Policy.class); when(owner.getAuthenticatedAsAccountId()).thenReturn(42L);
            when(owner.getAccountId()).thenReturn(77L); ApiContext.newContext().setPolicy(owner);
            ApiContext.getContext().setIdFormatter(new io.github.ibuildthecloud.gdapi.id.IdentityFormatter());
            var request = new ApiRequest(null, null); request.setType("loadBalancerService"); request.setMethod("POST"); request.setRequestObject(body);
            ApiContext.getContext().setApiRequest(request);
            var policy = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy(
                    io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Mode.FULL,
                    io.cattle.platform.iaas.api.auth.apikey.ApiKeyPolicy.Effect.ALLOW, null, List.of());
            io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext.attach(request,
                    new io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext(7, 42, "apiKey", 3, policy));
            request.setAttribute("apiKey.audit.requestId", id); request.setAttribute("apiKey.audit.decision", "ALLOW");
            request.setAttribute("apiKey.audit.keyId", "1c7"); request.setAttribute("apiKey.audit.operation", "create");
            var capture = new io.cattle.platform.iaas.api.auth.apikey.ApiKeyProcessAuthorization();
            observer.dispatch(request, id, () -> {
                io.cattle.platform.engine.process.ProcessAuthorization.prepare(config, List.of(capture, observer)); return null;
            });
            var record = new io.cattle.platform.engine.manager.impl.ProcessRecord(config, 901L, "focused");
            record.setData(store.json.readValue(store.json.writeValueAsString(record.getData())));
            var persisted = new io.cattle.platform.core.model.tables.records.ProcessInstanceRecord(); persisted.setId(901L);
            persisted.setAccountId(77L); persisted.setProcessName(record.getProcessName()); persisted.setResourceType(record.getResourceType());
            persisted.setResourceId(record.getResourceId()); persisted.setData(record.getData());
            when(observer.objects.loadResource(io.cattle.platform.core.model.ProcessInstance.class, 901L)).thenReturn(persisted);
            var captured = io.cattle.platform.engine.process.ProcessAuthorization.metadata(record);
            observer.beforeExecution(record, captured); assertEquals(901L, ((Number) store.execution(id).get("rootProcessId")).longValue());
            observer.beforeExecution(record, captured); backend.setRemoved(new java.util.Date());
            assertThrows(ProcessAuthorizationDeniedException.class, () -> observer.beforeExecution(record, captured));
            record.setEndTime(new java.util.Date());
            observer.afterExecution(record, captured, io.cattle.platform.engine.process.ExitReason.AUTHORIZATION_DENIED);
            assertEquals("ResourceChangeAuthorizationDenied", store.failure(id));
        }
    }

    private static Map<String, Object> rule(String id, String access, int source, int target) {
        return Map.of("protocol", "tcp", "access", access, "sourcePort", source, "targetPort", target, "serviceId", id);
    }
    private static Map<String, Object> input(boolean start) {
        return new LinkedHashMap<>(Map.of("name", "edge", "stackId", "1st3", "startOnCreate", start,
                "portRules", List.of(rule("1s9", "public", 8080, 80), rule("1s9", "internal", 9000, 90))));
    }
    private static final class Fixture extends AiResourceChangeDomainTest.Fixture {
        final ServiceRecord backend = new ServiceRecord(); final ResourceManager nativeOwner = mock(ResourceManager.class);
        Fixture() {
            when(schemas.getSchema(anyString())).thenReturn(schema);
            when(schema.getResourceMethods()).thenReturn(List.of("GET", "PUT")); when(schema.getCollectionMethods()).thenReturn(List.of("POST", "GET"));
            Map<String, io.github.ibuildthecloud.gdapi.model.Field> fields = new LinkedHashMap<>();
            for (String field : List.of("name", "description", "stackId", "startOnCreate", "lbConfig", "launchConfig", "scale")) {
                FieldImpl capability = new FieldImpl(); capability.setCreate(true); fields.put(field, capability);
            }
            when(schema.getResourceFields()).thenReturn(fields);
            domain.serviceCreateValidation = mock(ServiceCreateValidation.class);
            when(domain.serviceCreateValidation.imageUuid(anyString())).thenAnswer(call -> call.getArgument(0));
            domain.deploymentReadiness = mock(ServiceDeploymentReadiness.class);
            domain.targets = mock(ApiKeyTargetResolver.class);
            when(domain.targets.parseScopeId(anyString(), anyString())).thenAnswer(call ->
                    Long.parseLong(((String) call.getArgument(1)).replaceFirst("^1(?:st|s)", "")));
            var formatter = new io.cattle.platform.api.formatter.DefaultIdFormatter(); formatter.setSchemaFactory(schemas);
            when(schemas.getBaseType(anyString())).thenAnswer(call -> "stack".equals(call.getArgument(0)) ? "stack" : "service");
            formatter.setTypeMappings(Map.of("service", "s", "stack", "st")); ApiContext.getContext().setIdFormatter(formatter);
            backend.setId(9L); backend.setUuid("backend"); backend.setAccountId(77L); backend.setStackId(3L);
            backend.setKind("service"); backend.setName("db"); backend.setState("active");
            StackRecord stack = new StackRecord(); stack.setId(3L); stack.setUuid("stack"); stack.setAccountId(77L); stack.setKind("stack");
            stack.setName("Blue"); stack.setState("active");
            when(domain.objects.loadResource(Service.class, 9L)).thenReturn(backend); when(domain.objects.loadResource(Stack.class, 3L)).thenReturn(stack);
            domain.managers = mock(ResourceManagerLocator.class); when(domain.managers.getResourceManagerByType(anyString())).thenReturn(nativeOwner);
            when(nativeOwner.getById(eq("service"), eq("1s9"), any())).thenReturn(backend);
            when(nativeOwner.getById(eq("stack"), eq("1st3"), any())).thenReturn(stack);
        }
    }
}
