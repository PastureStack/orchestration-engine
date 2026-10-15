package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.*;

import io.cattle.platform.api.auth.Policy;
import io.cattle.platform.iaas.api.auditing.dao.AuditLogDao;
import io.cattle.platform.util.type.CollectionUtils;
import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractApiRequestHandler;
import io.github.ibuildthecloud.gdapi.request.parser.ApiRequestParser;
import io.github.ibuildthecloud.gdapi.servlet.ApiRequestFilterDelegate;
import io.github.ibuildthecloud.gdapi.url.NullUrlBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * In-process servlet integration, not native QA or a TCP/database test.
 * Reuses the delegate/servlet doubles from ApiRequestCompletionTest and the
 * real audit service/temp outbox/DAO receipt pattern from ApiKeyAuditTest.
 * No runtime source, global settings, server process or QA resource is changed.
 */
public class Aud04RequestCompletionIntegrationTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @After public void clearContext() { ApiContext.remove(); }

    @Test
    public void handlerFailurePersistsActual500AndDoesNotBecomeLateSuccess() throws Exception {
        Fixture fixture = new Fixture();
        fixture.delegate.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest request) {
                fixture.verifyDurableDecisionBeforeHandler();
                fixture.daoOnline.set(true);
                throw new IllegalStateException("fixture-private-handler-failure");
            }
        }));

        fixture.run();
        fixture.verifyFailureAndLateCompletions(500);
    }

    @Test
    public void timeoutAfterCommitted200PersistsFailureInsteadOfHttpSuccess() throws Exception {
        Fixture fixture = new Fixture();
        fixture.delegate.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest request) throws IOException {
                fixture.verifyDurableDecisionBeforeHandler();
                fixture.committed.set(true);
                request.setResponseCode(500); // Already-sent 200 headers remain authoritative.
                fixture.daoOnline.set(true);
                throw new SocketTimeoutException("fixture-private-timeout");
            }
        }));

        fixture.run();
        fixture.verifyFailureAndLateCompletions(200);
    }

    private class Fixture {
        final AtomicInteger actualStatus = new AtomicInteger(200);
        final AtomicBoolean committed = new AtomicBoolean();
        final AtomicBoolean daoOnline = new AtomicBoolean();
        final AtomicInteger offlineAttempts = new AtomicInteger();
        final AtomicReference<ApiRequest> parsedRequest = new AtomicReference<>();
        final List<Map<String, Object>> delivered = new ArrayList<>();
        final Map<String, Map<String, Object>> atomicSpoolReceipts = new HashMap<>();
        final ApiRequestFilterDelegate delegate = new ApiRequestFilterDelegate();
        final AuditServiceImpl service = new AuditServiceImpl();
        final JacksonMapper mapper = new JacksonMapper();
        final Path directory;
        final Policy policy = (Policy) Proxy.newProxyInstance(Policy.class.getClassLoader(),
                new Class<?>[]{Policy.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getAccountId")) return 1L;
                    if (method.getName().equals("getAuthenticatedAsAccountId")) return 2L;
                    return null;
                });
        final HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getServletPath")) return "/v2-beta/stacks/12";
                    if (method.getName().equals("getMethod")) return "GET";
                    return null;
                });
        final HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getStatus")) return actualStatus.get();
                    if (method.getName().equals("isCommitted")) return committed.get();
                    if (method.getName().equals("setStatus") && !committed.get()) actualStatus.set((Integer) args[0]);
                    if (method.getName().equals("sendError")) {
                        actualStatus.set((Integer) args[0]);
                        committed.set(true);
                    }
                    return null;
                });

        Fixture() throws Exception {
            directory = temporary.newFolder().toPath();
            mapper.init();
            service.jsonMapper = mapper;
            service.apiKeyOutbox = new ApiKeyAuditOutbox(directory, mapper);
            service.auditLogDao = (AuditLogDao) Proxy.newProxyInstance(AuditLogDao.class.getClassLoader(),
                    new Class<?>[]{AuditLogDao.class}, (proxy, method, args) -> {
                        assertEquals("createApiKeyEvent", method.getName());
                        Map<String, Object> data = new HashMap<>(CollectionUtils.castMap(args[2]));
                        assertEquals(1L, args[4]);
                        assertEquals(2L, args[5]);
                        // Production persist() has fsynced/atomically moved the file
                        // before its real deliverApiKeyEvent() invokes this DAO.
                        verifyAtomicSpoolReceipt(data);
                        if (!daoOnline.get()) {
                            offlineAttempts.incrementAndGet();
                            throw new IllegalStateException("fixture DAO offline");
                        }
                        delivered.add(data);
                        return null;
                    });
            SchemaFactory schemas = (SchemaFactory) Proxy.newProxyInstance(SchemaFactory.class.getClassLoader(),
                    new Class<?>[]{SchemaFactory.class}, (proxy, method, args) -> null);
            delegate.setSchemaFactories(Map.of("v2-beta", schemas));
            delegate.setParser(new ApiRequestParser() {
                @Override public String parseVersion(String path) { return "v2-beta"; }
                @Override public boolean parse(ApiRequest apiRequest) {
                    apiRequest.setUrlBuilder(new NullUrlBuilder());
                    apiRequest.setMethod("GET");
                    apiRequest.setType("stack");
                    apiRequest.setId("12");
                    apiRequest.setAttribute("apiKey.audit.keyId", "1c99");
                    apiRequest.setAttribute("apiKey.audit.decision", "ALLOW");
                    apiRequest.setAttribute("apiKey.audit.operation", "read");
                    apiRequest.setAttribute("apiKey.audit.targetType", "stack");
                    apiRequest.setAttribute("apiKey.audit.targetId", "12");
                    apiRequest.setAttribute("apiKey.audit.policyRevision", 3);
                    ApiContext.getContext().setPolicy(policy);
                    parsedRequest.set(apiRequest);
                    service.recordDecision(apiRequest, policy);
                    return true;
                }
            });
            // Use the real completion sink directly, not a mapper or a fake writer.
            delegate.setCompletionSinks(List.of(service));
        }

        void run() throws Exception {
            delegate.doFilter(request, response, (req, res) -> fail("Unexpected fallback chain"));
            assertNull(ApiContext.getContext());
        }

        void verifyDurableDecisionBeforeHandler() {
            assertTrue("The actual DAO fault must have been exercised", offlineAttempts.get() > 0);
            assertEquals(0, delivered.size());
            assertEquals(1, atomicSpoolReceipts.size());
            try {
                assertEquals(1, pendingFiles().size());
                assertFalse(Files.exists(directory.resolve("outbox.pending")));
            } catch (IOException error) {
                throw new AssertionError("Unable to inspect the owned test outbox", error);
            }
        }

        List<Path> pendingFiles() throws IOException {
            try (Stream<Path> paths = Files.list(directory)) {
                return paths.filter(path -> path.getFileName().toString().endsWith(".json")).toList();
            }
        }

        void verifyAtomicSpoolReceipt(Map<String, Object> deliveredData) throws IOException {
            assertFalse("Delivery must not observe an uncommitted temporary file", Files.exists(directory.resolve("outbox.pending")));
            String eventId = String.valueOf(deliveredData.get("eventId"));
            UUID.fromString(eventId);
            Map<String, Object> stored = null;
            for (Path path : pendingFiles()) {
                Map<?, ?> envelope = mapper.readValue(Files.readAllBytes(path), Map.class);
                Map<String, Object> data = CollectionUtils.castMap(envelope.get("data"));
                if (eventId.equals(data.get("eventId"))) {
                    assertNull("Each persisted event must have one atomic file", stored);
                    stored = new HashMap<>(data);
                }
            }
            assertNotNull("The real outbox file must exist before DAO delivery", stored);
            assertEquals(deliveredData, stored);
            Map<String, Object> previous = atomicSpoolReceipts.putIfAbsent(eventId, stored);
            if (previous != null) assertEquals("Retry must not rewrite an event", previous, stored);
        }

        void verifyFailureAndLateCompletions(int expectedStatus) throws Exception {
            assertEquals(expectedStatus, actualStatus.get());
            assertEquals(2, delivered.size());
            assertEquals(2, atomicSpoolReceipts.size());
            assertTrue("Acknowledged spool files must be removed", pendingFiles().isEmpty());
            assertFalse(Files.exists(directory.resolve("outbox.pending")));
            Map<String, Object> decision = delivered.stream().filter(row -> "decision".equals(row.get("phase"))).findFirst().orElseThrow();
            Map<String, Object> completion = delivered.stream().filter(row -> "response".equals(row.get("phase"))).findFirst().orElseThrow();
            String requestId = String.valueOf(decision.get("requestId"));
            UUID.fromString(requestId);
            assertEquals(requestId, completion.get("requestId"));
            assertNotEquals(decision.get("eventId"), completion.get("eventId"));
            assertEquals("PENDING", decision.get("outcome"));
            assertEquals("ALLOW", completion.get("decision"));
            assertEquals("FAILED", completion.get("outcome"));
            assertEquals(expectedStatus, ((Number) completion.get("httpStatus")).intValue());
            assertEquals(expectedStatus, ((Number) completion.get("responseCode")).intValue());
            assertEquals("1c99", completion.get("keyId"));
            assertEquals("read", completion.get("operation"));
            assertEquals("stack", completion.get("targetType"));
            assertEquals("12", completion.get("targetId"));
            assertFalse(atomicSpoolReceipts.toString().contains("fixture-private"));

            List<Map<String, Object>> before = new ArrayList<>();
            for (Map<String, Object> row : delivered) before.add(new HashMap<>(row));
            for (int lateStatus : new int[]{200, 101}) {
                parsedRequest.get().setResponseCode(lateStatus);
                service.complete(parsedRequest.get(), null);
                assertEquals("A second completion must not turn failure into success", before, delivered);
                assertEquals(2, atomicSpoolReceipts.size());
                assertTrue(pendingFiles().isEmpty());
            }
        }
    }
}
