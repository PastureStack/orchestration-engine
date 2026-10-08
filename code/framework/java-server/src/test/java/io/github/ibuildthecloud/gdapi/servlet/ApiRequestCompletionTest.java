package io.github.ibuildthecloud.gdapi.servlet;

import static org.junit.Assert.*;

import io.github.ibuildthecloud.gdapi.context.ApiContext;
import io.github.ibuildthecloud.gdapi.factory.SchemaFactory;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.handler.AbstractApiRequestHandler;
import io.github.ibuildthecloud.gdapi.request.handler.ApiRequestCompletionSink;
import io.github.ibuildthecloud.gdapi.request.parser.ApiRequestParser;
import io.github.ibuildthecloud.gdapi.url.NullUrlBuilder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ApiRequestCompletionTest {
    @Test
    public void eofAfterHeadersReportsActualStatusExactlyOnceWithContext() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger completions = new AtomicInteger();
        fixture.delegate.setCompletionSinks(List.of((request, failure) -> {
            assertEquals(200, request.getResponseCode());
            assertTrue(failure instanceof EOFException);
            assertSame(request, ApiContext.getContext().getApiRequest());
            completions.incrementAndGet();
        }));
        fixture.delegate.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest request) throws IOException {
                fixture.committed.set(true);
                request.setResponseCode(500); // Too late to change the actual headers.
                throw new EOFException("private stream payload");
            }
        }));
        try {
            fixture.run();
            fail("EOF must still propagate");
        } catch (EOFException expected) { }
        assertEquals(1, completions.get());
        assertNull(ApiContext.getContext());
    }

    @Test
    public void unhandledFailureReportsServerErrorAndCompletionFailureCannotReturnSuccess() throws Exception {
        Fixture fixture = new Fixture();
        fixture.delegate.setCompletionSinks(List.of((request, failure) -> {
            assertEquals(500, request.getResponseCode());
            assertNotNull(failure);
        }));
        fixture.delegate.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest request) { throw new IllegalStateException("private payload"); }
        }));
        fixture.run();
        assertEquals(500, fixture.status.get());
        assertNull(ApiContext.getContext());

        fixture = new Fixture();
        fixture.delegate.setHandlers(List.of());
        fixture.delegate.setCompletionSinks(List.of((request, failure) -> { throw new IllegalStateException("private JDBC values"); }));
        try {
            fixture.run();
            fail("Completion persistence failure must be observable");
        } catch (IOException expected) {
            assertEquals("Request completion persistence is unavailable", expected.getMessage());
            assertNull(expected.getCause());
        }
        assertNull(ApiContext.getContext());
    }

    @Test
    public void acceptedStatusDoesNotBecomeCompletionSuccess() throws Exception {
        Fixture fixture = new Fixture();
        AtomicInteger completions = new AtomicInteger();
        fixture.delegate.setHandlers(List.of(new AbstractApiRequestHandler() {
            @Override public void handle(ApiRequest request) { request.setResponseCode(202); }
        }));
        fixture.delegate.setCompletionSinks(List.of((request, failure) -> {
            assertEquals(202, request.getResponseCode());
            assertNull(failure);
            completions.incrementAndGet();
        }));
        fixture.run();
        assertEquals(1, completions.get());
    }

    private static class Fixture {
        final AtomicInteger status = new AtomicInteger(200);
        final AtomicBoolean committed = new AtomicBoolean();
        final ApiRequestFilterDelegate delegate = new ApiRequestFilterDelegate();
        final HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class}, (proxy, method, args) -> method.getName().equals("getServletPath") ? "/v2-beta/stacks" : null);
        final HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(HttpServletResponse.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getStatus")) return status.get();
                    if (method.getName().equals("isCommitted")) return committed.get();
                    if (method.getName().equals("setStatus") && !committed.get()) status.set((Integer) args[0]);
                    if (method.getName().equals("sendError")) { status.set((Integer) args[0]); committed.set(true); }
                    return null;
                });

        Fixture() {
            SchemaFactory schemas = (SchemaFactory) Proxy.newProxyInstance(SchemaFactory.class.getClassLoader(),
                    new Class<?>[]{SchemaFactory.class}, (proxy, method, args) -> null);
            delegate.setSchemaFactories(Map.of("v2-beta", schemas));
            delegate.setParser(new ApiRequestParser() {
                @Override public String parseVersion(String path) { return "v2-beta"; }
                @Override public boolean parse(ApiRequest request) {
                    request.setUrlBuilder(new NullUrlBuilder());
                    request.setAttribute("apiKey.audit.keyId", "1a1");
                    return true;
                }
            });
        }

        void run() throws Exception { delegate.doFilter(request, response, (request, response) -> fail("Unexpected fallback chain")); }
    }
}
