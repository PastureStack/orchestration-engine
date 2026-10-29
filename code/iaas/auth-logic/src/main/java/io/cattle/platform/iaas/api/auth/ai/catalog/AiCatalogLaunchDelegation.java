package io.cattle.platform.iaas.api.auth.ai.catalog;

import static io.cattle.platform.iaas.api.auth.ai.catalog.AiCatalogLaunchInput.*;

import io.cattle.platform.api.auth.ApiAuditPayload;
import io.cattle.platform.core.constants.AccountConstants;
import io.cattle.platform.core.model.Account;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.iaas.api.auth.apikey.ApiKeyCredentialContext;
import io.cattle.platform.iaas.api.auth.impl.ApiAuthenticator;
import io.cattle.platform.object.util.ObjectUtils;
import io.cattle.platform.process.common.handler.ProcessEventDecorator;
import io.github.ibuildthecloud.gdapi.model.Resource;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.ApiRequestCompletionSink;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One event-local, exact-graph capability for the authenticated internal Compose launcher. */
public class AiCatalogLaunchDelegation implements ProcessEventDecorator, ApiRequestCompletionSink,
        io.cattle.platform.api.auth.CatalogLaunchTransformAuthorization {
    public static final String HEADER = "X-PastureStack-Catalog-Launch-Delegation";
    private static final String VERIFIED = AiCatalogLaunchDelegation.class.getName();
    private static final String CLAIM = VERIFIED + ".claim";
    record Verified(String planId, String operation, String nodeType, String name, boolean bootstrap) { }
    public record Admission(ApiAuthenticator.CurrentAuthorization authorization, Account actor) { }
    @Inject AiCatalogLaunchPlanStore store;
    @Inject AiCatalogLaunchAccess access;
    @Inject AiCatalogLaunchPrivate privateData;
    @Inject AiCatalogLaunchGraph graphs;
    @Inject AiCatalogLaunchGateway gateway;
    @Inject AiCatalogLaunchPlanService plans;
    Clock clock = Clock.systemUTC();
    final SecureRandom random = new SecureRandom();

    public static boolean bootstrap(ApiRequest request) {
        return request != null && request.getAttribute(VERIFIED) instanceof Verified verified && verified.bootstrap();
    }

    @Override public void authorize(ApiRequest request, Map<String, Object> inspect) {
        if (request.getServletContext() == null || request.getServletContext().getRequest().getHeader(HEADER) == null) return;
        request.setRequestObject(inspect);
        try { access.authenticator.handle(request); }
        catch (java.io.IOException unavailable) { throw error(503, "CatalogLaunchUnavailable"); }
        if (!bootstrap(request)) throw error(403, "CatalogLaunchDelegationDenied");
    }

    @Override public void decorate(ProcessState state, ProcessInstance process, EventVO<Object> event) {
        if (!(state.getResource() instanceof Stack stack) || !"stack.create".equals(process.getName())
                || !"stack.create;handler=compose-executor".equals(event.getName())) return;
        Map<String, Object> found = store.forStack(stack.getId());
        if (found == null) return; // Ordinary Stack creation retains its original event and authority path.
        store.independent(() -> {
            Map<String, Object> plan = store.loadForUpdate((String) found.get("id")); requireActive(plan);
            ApiRequest request = new ApiRequest(null, null); request.setVersion("v2-beta"); request.setSchemaVersion("v2-beta");
            var authorization = access.bound(plan, request); access.catalog(authorization);
            Map<String, Object> values = privateData.open(plan); frozenStack(plan, values);
            String proof;
            if (plan.get("delegationEventId") != null) {
                // Existing retries reuse this exact event capability and its ledger;
                // they cannot mint a fresh execution after an unknown child write.
                event.setId((String) plan.get("delegationEventId")); proof = (String) values.get("delegationProof");
            } else {
                byte[] nonce = new byte[32]; random.nextBytes(nonce);
                proof = "cld1." + ((String) plan.get("id")).substring("aicataloglaunch-".length()) + "."
                        + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(nonce);
                plan.put("delegationEventId", event.getId());
                plan.put("delegationBindingDigest", binding(plan, values));
                plan.put("delegationProofHash", hash(proof + ":" + plan.get("delegationBindingDigest")));
                plan.put("delegationExpiresAt", clock.millis() + 900000); values.put("delegationProof", proof);
                plan.put("privateEnvelope", privateData.seal(plan, values)); store.save(plan);
            }
            Map<String, Object> data = event.getData() == null ? new LinkedHashMap<>() : map(event.getData());
            data.put("catalogLaunchDelegation", Map.of("planId", plan.get("id"), "stackId", wire("stack", stack.getId()),
                    "eventId", event.getId(), "proof", proof));
            event.setData(data); return null;
        });
    }

    /** Invoked only after the ordinary authentication provider verified the caller's credential. */
    public Admission admit(ApiRequest request, Account authenticated) {
        String proof = request.getServletContext() == null ? null : request.getServletContext().getRequest().getHeader(HEADER);
        if (proof == null) {
            if (launcher(authenticated) && targetsActiveExecution(request)) throw error(403, "CatalogLaunchDelegationRequired");
            return null;
        }
        if (!launcher(authenticated) || !proof.matches("cld1\\.[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}")) throw error(403, "CatalogLaunchDelegationDenied");
        String planId = "aicataloglaunch-" + proof.substring(5, 41);
        return store.independent(() -> {
            Map<String, Object> plan = store.loadForUpdate(planId);
            if (plan == null) throw error(403, "CatalogLaunchDelegationDenied");
            if (!(plan.get("delegationBindingDigest") instanceof String bindingDigest) || !bindingDigest.matches("[0-9a-f]{64}")
                    || !constant(hash(proof + ":" + bindingDigest), Objects.toString(plan.get("delegationProofHash"), "")))
                throw error(403, "CatalogLaunchDelegationDenied");
            // The proof is authentic before any live-policy rejection. Only now
            // may generic audit omit this private request/response payload.
            ApiAuditPayload.verifiedCatalogLaunch(request);
            Map<String, Object> values = privateData.open(plan);
            if (!bindingDigest.equals(binding(plan, values))) throw error(403, "CatalogLaunchDelegationDenied");
            requireActive(plan);
            if (!(plan.get("delegationExpiresAt") instanceof Number expiry) || clock.millis() >= expiry.longValue())
                throw error(403, "CatalogLaunchDelegationExpired");
            var authority = access.bound(plan, request); access.catalog(authority);
            frozenStack(plan, values);
            Object project = request.getAttribute(io.cattle.platform.core.constants.ProjectConstants.PROJECT_HEADER);
            String projectHeader = request.getServletContext().getRequest().getHeader(io.cattle.platform.core.constants.ProjectConstants.PROJECT_HEADER);
            long account = ((Number) plan.get("accountId")).longValue();
            if (project != null && !sameId("project", project.toString(), account)
                    || projectHeader != null && !sameId("project", projectHeader, account)) throw error(403, "CatalogLaunchDelegationDenied");
            Map<String, Object> material = gateway.material(access.project(authority.current().policy()),
                    (String) plan.get("templateId"), (String) plan.get("versionId"));
            if (!Objects.equals(values.get("digest"), material.get("digest"))) throw error(409, "CatalogLaunchSourceChanged");
            if (!Objects.equals(values.get("questionSchemaDigest"), graphs.digest(questions(material.get("questionSchema")))))
                throw error(409, "CatalogLaunchSourceChanged");
            Verified verified = match(request, plan, values);
            request.setAttribute(VERIFIED, verified); access.markDomain(request);
            if (authority.key() != null) ApiKeyCredentialContext.attach(request, authority.key());
            AiCatalogLaunchAccess.inContext(request, authority.current().policy(), () -> {
                plans.audit(request, "delegate", plan, true, "CatalogLaunchDelegationAllowed"); return null;
            });
            if (verified.operation() != null) claim(plan, verified, request);
            Account actor = access.objects.loadResource(Account.class, authority.current().policy().getAuthenticatedAsAccountId());
            return new Admission(authority.current(), actor);
        });
    }

    Verified match(ApiRequest request, Map<String, Object> plan, Map<String, Object> values) {
        if (request.getLink() != null) throw error(403, "CatalogLaunchDelegationDenied");
        String type = request.getType(), method = request.getMethod(), id = request.getId(), action = request.getAction();
        Map<String, Object> graph = map(values.get("graph")), created = AiCatalogLaunchMaterializer.nullableMap(plan.get("createdGraph"));
        String stackId = wire("stack", ((Number) plan.get("internalStackId")).longValue());
        if ("GET".equals(method) && action == null) {
            if ("schema".equals(type) && id == null && parameters(request).isEmpty()) return new Verified((String) plan.get("id"), null, null, null, true);
            if ("project".equals(type) && sameId("project", id, ((Number) plan.get("accountId")).longValue()) && parameters(request).isEmpty())
                return new Verified((String) plan.get("id"), null, null, null, false);
            if ("stack".equals(type) && sameId("stack", id, ((Number) plan.get("internalStackId")).longValue()) && parameters(request).isEmpty())
                return new Verified((String) plan.get("id"), null, null, null, false);
            if (id != null && Set.of("service", "volumeTemplate").contains(type) && action == null && parameters(request).isEmpty()
                    && created.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith(type + ":")
                    && entry.getValue() instanceof Number number && sameId(type, id, number.longValue())))
                return new Verified((String) plan.get("id"), null, null, null, false);
            if (id == null) {
                Map<String, String> params = parameters(request);
                if (Set.of("service", "volumeTemplate").contains(type) && Set.of("name", "stackId", "removed_null").containsAll(params.keySet())
                        && stackId.equals(params.get("stackId")) && named(graph, type, params.get("name")) != null
                        && (!params.containsKey("removed_null") || Set.of("true", "<nil>").contains(params.get("removed_null"))))
                    return new Verified((String) plan.get("id"), null, null, null, false);
                if ("secret".equals(type) && params.size() == 1 && params.containsKey("name")
                        && map(values.get("authorizedReferenceIds")).containsKey(params.get("name")))
                    return new Verified((String) plan.get("id"), null, null, null, false);
                if ("serviceConsumeMap".equals(type) && params.size() == 1 && params.containsKey("serviceId")
                        && created.entrySet().stream().anyMatch(entry -> entry.getKey().startsWith("service:")
                        && entry.getValue() instanceof Number number && sameId("service", params.get("serviceId"), number.longValue())))
                    return new Verified((String) plan.get("id"), null, null, null, false);
            }
        }
        if ("POST".equals(method) && "scripts".equals(type) && "transform".equals(id) && action == null && parameters(request).isEmpty()) {
            Object body = body(request);
            if (graph.get("inspect") instanceof List<?> rows && rows.stream().anyMatch(value ->
                    graphs.digest(map(value).get("dockerInspect")).equals(graphs.digest(body))))
                return new Verified((String) plan.get("id"), null, null, null, true);
        }
        if (!"POST".equals(method) || !parameters(request).isEmpty()) throw error(403, "CatalogLaunchDelegationDenied");
        if (id == null && action == null && Set.of("service", "volumeTemplate").contains(type)) {
            Map<String, Object> body = body(request), node = named(graph, type, Objects.toString(body.get("name"), null));
            if (node != null && graphs.digest(graphs.bind(node.get("body"), stackId)).equals(graphs.digest(body)))
                return new Verified((String) plan.get("id"), "create:" + type + ":" + node.get("name"), type, (String) node.get("name"), false);
        }
        if ("service".equals(type) && id != null && Set.of("activate", "setservicelinks").contains(action == null ? "" : action)) {
            String name = created.entrySet().stream().filter(entry -> entry.getKey().startsWith("service:")
                    && entry.getValue() instanceof Number number && sameId("service", id, number.longValue()))
                    .map(entry -> entry.getKey().substring("service:".length())).findFirst().orElse(null);
            if (name != null) {
                if ("activate".equals(action) && Boolean.TRUE.equals(plan.get("startOnCreate")) && body(request).isEmpty())
                    return new Verified((String) plan.get("id"), "activate:" + name, "service", name, false);
                if ("setservicelinks".equals(action) && linksSubset(map(named(graph, "service", name).get("links")), body(request), created))
                    return new Verified((String) plan.get("id"), "links:" + name, "service", name, false);
            }
        }
        throw error(403, "CatalogLaunchDelegationDenied");
    }

    void claim(Map<String, Object> plan, Verified verified, ApiRequest request) {
        Map<String, Object> ledger = AiCatalogLaunchMaterializer.nullableMap(plan.get("ledger"));
        String digest = graphs.digest(body(request));
        Map<String, Object> prior = ledger.get(verified.operation()) == null ? null : map(ledger.get(verified.operation()));
        if (prior != null) {
            if (!"completed".equals(prior.get("state"))) throw error(409, "CatalogLaunchDispatchUncertain");
            if (!verified.operation().startsWith("links:") && !verified.operation().startsWith("activate:"))
                throw error(409, "CatalogLaunchAlreadyDispatched");
            if (verified.operation().startsWith("activate:") && !digest.equals(prior.get("digest"))) throw error(403, "CatalogLaunchDelegationDenied");
            if (verified.operation().startsWith("links:") && prior.get("linkHashes") instanceof List<?> previous) {
                List<String> incoming = linkHashes(body(request));
                if (!incoming.containsAll(previous)) throw error(403, "CatalogLaunchDelegationDenied");
            }
        }
        String claimId = java.util.UUID.randomUUID().toString(); request.setAttribute(CLAIM, claimId);
        Map<String, Object> entry = new LinkedHashMap<>(); entry.put("state", "inflight"); entry.put("digest", digest); entry.put("claimId", claimId);
        if (verified.operation().startsWith("links:")) entry.put("linkHashes", linkHashes(body(request)));
        ledger.put(verified.operation(), entry); plan.put("ledger", ledger); store.save(plan);
    }

    /** Joins the normal manager's write transaction; both the resource and its ledger commit before HTTP output. */
    void managerCompleted(ApiRequest request, Object response) {
        if (!(request.getAttribute(VERIFIED) instanceof Verified verified) || verified.operation() == null) return;
        Map<String, Object> plan = store.loadForUpdate(verified.planId());
        if (plan == null) throw error(409, "CatalogLaunchDispatchUncertain");
        Map<String, Object> ledger = map(plan.get("ledger")), entry = map(ledger.get(verified.operation()));
        if (!Objects.equals(request.getAttribute(CLAIM), entry.get("claimId")) || !"inflight".equals(entry.get("state")))
            throw error(409, "CatalogLaunchDispatchUncertain");
        entry.put("state", "completed");
        if (verified.operation().startsWith("create:")) {
                String id = response instanceof Resource resource ? resource.getId() : Objects.toString(ObjectUtils.getId(response), null);
                Long raw = internal(verified.nodeType(), id);
                if (raw == null) throw error(409, "CatalogLaunchDispatchUncertain");
                Map<String, Object> created = AiCatalogLaunchMaterializer.nullableMap(plan.get("createdGraph"));
                created.put(verified.nodeType() + ":" + verified.name(), raw); plan.put("createdGraph", created);
        }
        ledger.put(verified.operation(), entry); plan.put("ledger", ledger);
        if (verified.operation().startsWith("links:")) {
                Map<String, Object> values = privateData.open(plan), graph = map(values.get("graph"));
                Map<String, Object> linked = AiCatalogLaunchMaterializer.nullableMap(plan.get("linkedGraph"));
                linked.put(verified.name(), entry.get("linkHashes")); plan.put("linkedGraph", linked);
                Map<String, Object> created = AiCatalogLaunchMaterializer.nullableMap(plan.get("createdGraph"));
                boolean full = ((List<?>) graph.get("services")).stream()
                        .allMatch(value -> created.containsKey("service:" + map(value).get("name")));
                for (Object value : (List<?>) graph.get("services")) {
                    Map<String, Object> node = map(value);
                    full &= linkHashes(bindLinks(map(node.get("links")), created)).equals(linked.get(node.get("name")));
                }
                plan.put("linksSettled", full);
        }
        store.save(plan);
    }

    /** A rolled-back/failed write stays unknown. A committed manager result survives a later transport failure. */
    @Override public void complete(ApiRequest request, Throwable failure) {
        if (!(request.getAttribute(VERIFIED) instanceof Verified verified) || verified.operation() == null) return;
        store.independent(() -> {
            Map<String, Object> plan = store.loadForUpdate(verified.planId());
            if (plan == null) return null;
            Map<String, Object> ledger = map(plan.get("ledger")), entry = map(ledger.get(verified.operation()));
            if (!Objects.equals(request.getAttribute(CLAIM), entry.get("claimId")) || "completed".equals(entry.get("state"))) return null;
            entry.put("state", "unknown"); ledger.put(verified.operation(), entry); plan.put("ledger", ledger);
            plan.put("status", "unknown"); plan.put("reason", "CatalogLaunchDispatchUncertain"); store.save(plan); return null;
        });
    }

    private void frozenStack(Map<String, Object> plan, Map<String, Object> values) {
        Stack stack = access.objects.loadResource(Stack.class, ((Number) plan.get("internalStackId")).longValue());
        frozenStack(plan, values, stack);
    }

    private void frozenStack(Map<String, Object> plan, Map<String, Object> values, Stack stack) {
        if (stack == null || stack.getRemoved() != null || !"stack".equals(stack.getKind())
                || !Objects.equals(stack.getAccountId(), ((Number) plan.get("accountId")).longValue()))
            throw error(403, "OwnerPermissionDenied");
        Map<String, Object> actual = new LinkedHashMap<>(), expected = map(values.get("stackBody"));
        for (String field : expected.keySet()) actual.put(field, ObjectUtils.getValue(stack, field));
        if (!graphs.digest(expected).equals(graphs.digest(actual))) throw error(409, "CatalogLaunchSourceChanged");
    }

    void validateResponse(ApiRequest request, List<?> rows) {
        if (request == null || !(request.getAttribute(VERIFIED) instanceof Verified verified) || !"GET".equals(request.getMethod())) return;
        Map<String, Object> plan = store.load(verified.planId());
        if (plan == null) throw error(403, "CatalogLaunchDelegationDenied");
        Map<String, Object> values = privateData.open(plan);
        String type = request.getType();
        if ("stack".equals(type)) {
            if (rows.size() != 1 || !(rows.getFirst() instanceof Stack stack)) throw error(409, "CatalogLaunchSourceChanged");
            frozenStack(plan, values, stack); return;
        }
        if (!Set.of("service", "volumeTemplate", "secret").contains(type)) return;
        if (rows.size() > 1) throw error(409, "CatalogLaunchSourceChanged");
        Map<String, Object> created = AiCatalogLaunchMaterializer.nullableMap(plan.get("createdGraph"));
        for (Object row : rows) {
            Long id = internal(type, Objects.toString(ObjectUtils.getId(row), null));
            Object expected;
            if ("secret".equals(type)) expected = map(values.get("authorizedReferenceIds")).get(parameters(request).get("name"));
            else expected = created.get(type + ":" + ObjectUtils.getValue(row, "name"));
            if (id == null || expected == null || !Objects.equals(id, internal(type, expected.toString())))
                throw error(409, "CatalogLaunchSourceChanged");
        }
    }

    private void requireActive(Map<String, Object> plan) {
        if (plan == null || !Boolean.TRUE.equals(AiCatalogLaunchOptionsService.enabled()) || Boolean.TRUE.equals(plan.get("delegationClosed"))
                || !Set.of("dispatching", "accepted", "running", "unknown").contains(plan.get("status"))
                || !"approved".equals(plan.get("confirmationState")) || plan.get("internalStackId") == null)
            throw error(403, "CatalogLaunchDelegationDenied");
    }

    private boolean targetsActiveExecution(ApiRequest request) {
        if ("GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod()) || "OPTIONS".equals(request.getMethod())) return false;
        Long stackId = null;
        if (request.getRequestObject() instanceof Map<?, ?> body) stackId = internal("stack", Objects.toString(body.get("stackId"), null));
        if ("service".equals(request.getType()) && request.getId() != null) {
            Long id = internal("service", request.getId()); Service service = id == null ? null : access.objects.loadResource(Service.class, id);
            if (service != null) stackId = service.getStackId();
        }
        if ("stack".equals(request.getType()) && request.getId() != null) stackId = internal("stack", request.getId());
        if ("volumeTemplate".equals(request.getType()) && request.getId() != null) {
            Long id = internal("volumeTemplate", request.getId());
            Object template = id == null ? null : access.objects.loadResource("volumeTemplate", id);
            Object parent = ObjectUtils.getValue(template, "stackId");
            if (parent instanceof Number number) stackId = number.longValue();
        }
        if (stackId == null) return false;
        Map<String, Object> plan = store.forStack(stackId);
        return plan != null && !Boolean.TRUE.equals(plan.get("delegationClosed"))
                && Set.of("dispatching", "accepted", "running", "unknown").contains(plan.get("status"));
    }

    private Map<String, Object> named(Map<String, Object> graph, String type, String name) {
        Object rows = graph.get("service".equals(type) ? "services" : "volumes");
        if (!(rows instanceof List<?> list)) return null;
        return list.stream().map(AiCatalogLaunchInput::map).filter(node -> type.equals(node.get("type")) && Objects.equals(name, node.get("name")))
                .findFirst().orElse(null);
    }

    private boolean linksSubset(Map<String, Object> intended, Object actual, Map<String, Object> created) {
        Map<String, Object> body = closed(actual, Set.of("serviceLinks"));
        Object links = body.get("serviceLinks"), full = bindLinks(intended, created).get("serviceLinks");
        if (!(links instanceof List<?> rows) || !(full instanceof List<?> expected)) return false;
        Set<String> unique = new java.util.HashSet<>();
        return rows.stream().allMatch(row -> unique.add(graphs.digest(row)) && expected.stream().anyMatch(value ->
                graphs.digest(value).equals(graphs.digest(row))));
    }

    private Map<String, Object> bindLinks(Map<String, Object> intended, Map<String, Object> created) {
        Map<String, Object> result = new LinkedHashMap<>(intended); List<Map<String, Object>> links = new ArrayList<>();
        if (!(intended.get("serviceLinks") instanceof List<?> rows)) throw error(422, "CatalogLaunchUnsupported");
        for (Object value : rows) {
            Map<String, Object> row = new LinkedHashMap<>(map(value));
            Object id = row.get("serviceId");
            if (!(id instanceof String symbol) || !symbol.startsWith("$service:")) throw error(422, "CatalogLaunchUnsupported");
            Object actual = created.get("service:" + symbol.substring("$service:".length()));
            if (actual instanceof Number number) { row.put("serviceId", wire("service", number.longValue())); links.add(row); }
        }
        result.put("serviceLinks", links); return result;
    }

    private List<String> linkHashes(Map<String, Object> body) {
        if (!(body.get("serviceLinks") instanceof List<?> links)) throw error(403, "CatalogLaunchDelegationDenied");
        return links.stream().map(graphs::digest).sorted().toList();
    }

    private String binding(Map<String, Object> plan, Map<String, Object> values) {
        Map<String, Object> bound = new LinkedHashMap<>();
        for (String field : List.of("id", "actorId", "accountId", "credentialId", "credentialRevision", "credentialKind", "internalStackId",
                "acceptedAt", "delegationEventId"))
            bound.put(field, plan.get(field));
        bound.put("sourceDigest", values.get("digest")); bound.put("graphDigest", values.get("graphDigest"));
        bound.put("authorizedReferenceIdsDigest", graphs.digest(values.get("authorizedReferenceIds")));
        bound.put("questionSchemaDigest", values.get("questionSchemaDigest"));
        bound.put("stackDigest", graphs.digest(values.get("stackBody")));
        return graphs.digest(bound);
    }

    private Map<String, String> parameters(ApiRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        if (request.getRequestParams() == null) return result;
        for (var entry : request.getRequestParams().entrySet()) {
            Object value = io.github.ibuildthecloud.gdapi.util.RequestUtils.makeSingularIfCan(entry.getValue());
            if ("action".equals(entry.getKey()) && Objects.equals(value, request.getAction())) continue;
            if (value instanceof String text) result.put(entry.getKey(), text);
            else if (value instanceof List<?> list && list.size() == 1 && list.getFirst() instanceof String text) result.put(entry.getKey(), text);
            else throw error(403, "CatalogLaunchDelegationDenied");
        }
        return result;
    }

    private Map<String, Object> body(ApiRequest request) {
        Map<String, Object> body = request.getRequestObject() == null ? new LinkedHashMap<>() : map(request.getRequestObject());
        if (body.containsKey("action")) {
            Object routed = request.getRequestParams() == null ? null
                    : io.github.ibuildthecloud.gdapi.util.RequestUtils.makeSingularIfCan(request.getRequestParams().get("action"));
            if (!Objects.equals(body.remove("action"), request.getAction()) || !Objects.equals(routed, request.getAction()))
                throw error(403, "CatalogLaunchDelegationDenied");
        }
        return body;
    }

    private boolean launcher(Account account) {
        return account != null && "machineServiceAccount".equals(account.getUuid()) && AccountConstants.SERVICE_KIND.equals(account.getKind())
                && account.getRemoved() == null && "active".equals(account.getState());
    }
    private boolean sameId(String type, String id, long expected) { return Objects.equals(internal(type, id), expected); }
    private Long internal(String type, String id) {
        try {
            if (id == null) return null;
            String raw = id.matches("[1-9][0-9]*") ? id : access.idFormatter.parseId(id);
            if (raw == null || !raw.matches("[1-9][0-9]*")) return null;
            long result = Long.parseLong(raw);
            return id.equals(raw) || id.equals(wire(type, result)) ? result : null;
        } catch (NumberFormatException invalid) { return null; }
    }
    private String wire(String type, long id) { return String.valueOf(access.idFormatter.formatId(type, id)); }
    private String hash(String proof) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(proof.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw error(503, "CatalogLaunchUnavailable"); }
    }
    private boolean constant(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.US_ASCII), right.getBytes(StandardCharsets.US_ASCII));
    }
}
