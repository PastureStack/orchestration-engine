package io.cattle.platform.iaas.api.auth.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.cattle.platform.core.model.AuthToken;
import io.cattle.platform.core.model.tables.records.AuthTokenRecord;
import io.cattle.platform.iaas.api.auth.dao.AuthTokenDao;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.server.model.ApiServletContext;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.Test;

public class TokenResourceManagerTest {

    private static final String VALID_SESSION = "1726358400000.0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_SESSION = "1726358400001.fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    @Test
    public void boundTokenRequiresMatchingClientSessionAndNeverEmitsExpiryCookie() {
        FakeAuthTokenDao dao = new FakeAuthTokenDao(record("token-key", VALID_SESSION));
        TestManager manager = manager(dao, "Bearer token-key");

        RequestState missing = request(null);
        assertSame(missing.object, manager.deleteToken(missing.object, missing.request));
        assertEquals(204, missing.request.getResponseCode());
        assertEquals(0, dao.deleteCount);
        assertTrue(missing.setCookies.isEmpty());

        RequestState mismatch = request(OTHER_SESSION);
        assertSame(mismatch.object, manager.deleteToken(mismatch.object, mismatch.request));
        assertEquals(0, dao.deleteCount);
        assertTrue(mismatch.setCookies.isEmpty());

        RequestState malformed = request("not-a-generation");
        assertSame(malformed.object, manager.deleteToken(malformed.object, malformed.request));
        assertEquals(204, malformed.request.getResponseCode());
        assertEquals(0, dao.deleteCount);
        assertTrue(malformed.setCookies.isEmpty());

        RequestState match = request(VALID_SESSION);
        assertSame(match.object, manager.deleteToken(match.object, match.request));
        assertEquals(1, dao.deleteCount);
        assertTrue(match.setCookies.isEmpty());
    }

    @Test
    public void missingAndAlreadyRevokedDeletesAreIdempotent() {
        FakeAuthTokenDao dao = new FakeAuthTokenDao(record("token-key", VALID_SESSION));
        TestManager manager = manager(dao, "token-key");

        RequestState first = request(VALID_SESSION);
        manager.deleteToken(first.object, first.request);
        RequestState second = request(VALID_SESSION);
        manager.deleteToken(second.object, second.request);

        assertEquals(1, dao.deleteCount);
        assertEquals(204, first.request.getResponseCode());
        assertEquals(204, second.request.getResponseCode());
        assertTrue(first.setCookies.isEmpty());
        assertTrue(second.setCookies.isEmpty());

        manager.current.setJwt(null);
        RequestState absent = request(null);
        assertSame(absent.object, manager.deleteToken(absent.object, absent.request));
        assertEquals(204, absent.request.getResponseCode());
    }

    @Test
    public void legacyTokenRetainsCompatibleDeleteAndExpiryCookie() {
        FakeAuthTokenDao dao = new FakeAuthTokenDao(record("legacy-key", null));
        TestManager manager = manager(dao, "legacy-key");
        RequestState state = request(null);

        assertSame(state.object, manager.deleteToken(state.object, state.request));

        assertEquals(1, dao.deleteCount);
        assertEquals(1, state.setCookies.size());
        assertTrue(state.setCookies.get(0).contains("Max-Age=0"));
        assertTrue(state.setCookies.get(0).contains("Secure"));
        assertTrue(state.setCookies.get(0).contains("SameSite=Lax"));
    }

    @Test
    public void clientSessionFormatIsStrictAndComparisonIsNullSafe() {
        assertEquals(VALID_SESSION, TokenResourceManager.normalizeClientSessionId(VALID_SESSION));
        assertNull(TokenResourceManager.normalizeClientSessionId(null));
        assertNull(TokenResourceManager.normalizeClientSessionId(""));
        assertTrue(TokenResourceManager.constantTimeEquals(VALID_SESSION, VALID_SESSION));
        assertFalse(TokenResourceManager.constantTimeEquals(VALID_SESSION, OTHER_SESSION));
        assertFalse(TokenResourceManager.constantTimeEquals(VALID_SESSION, null));

        try {
            TokenResourceManager.normalizeClientSessionId("not-a-generation");
            fail("invalid client session IDs must be rejected");
        } catch (ClientVisibleException expected) {
            assertEquals(400, expected.getStatus());
        }
    }

    @Test
    public void normalizesCookieAndBearerTransportsWithoutAcceptingOtherSchemes() {
        assertEquals("token-key", TokenResourceManager.normalizeTokenKey("token-key"));
        assertEquals("token-key", TokenResourceManager.normalizeTokenKey("Bearer token-key"));
        assertEquals("token-key", TokenResourceManager.normalizeTokenKey("bearer   token-key"));
        assertNull(TokenResourceManager.normalizeTokenKey(null));
        assertNull(TokenResourceManager.normalizeTokenKey(""));
        assertNull(TokenResourceManager.normalizeTokenKey("Basic token-key"));
        assertNull(TokenResourceManager.normalizeTokenKey("Bearer token-key extra"));
    }

    private static TestManager manager(FakeAuthTokenDao dao, String jwt) {
        TestManager manager = new TestManager();
        manager.authTokenDao = dao;
        manager.current = new Token();
        manager.current.setJwt(jwt);
        return manager;
    }

    private static AuthTokenRecord record(String key, String clientSessionId) {
        AuthTokenRecord record = new AuthTokenRecord();
        record.setKey(key);
        record.setClientSessionId(clientSessionId);
        return record;
    }

    private static RequestState request(String clientSessionId) {
        RequestState state = new RequestState();
        HttpServletRequest servletRequest = (HttpServletRequest) Proxy.newProxyInstance(
                TokenResourceManagerTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getHeader".equals(method.getName()) && TokenResourceManager.CLIENT_SESSION_HEADER.equals(args[0])) {
                        return clientSessionId;
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().equals(int.class)) {
                        return 0;
                    }
                    if (method.getReturnType().equals(long.class)) {
                        return 0L;
                    }
                    return null;
                });
        HttpServletResponse servletResponse = (HttpServletResponse) Proxy.newProxyInstance(
                TokenResourceManagerTest.class.getClassLoader(),
                new Class<?>[]{HttpServletResponse.class},
                (proxy, method, args) -> {
                    if ("addHeader".equals(method.getName()) && "Set-Cookie".equals(args[0])) {
                        state.setCookies.add((String) args[1]);
                    }
                    if (method.getReturnType().equals(boolean.class)) {
                        return false;
                    }
                    if (method.getReturnType().equals(int.class)) {
                        return 0;
                    }
                    if (method.getReturnType().equals(long.class)) {
                        return 0L;
                    }
                    return null;
                });
        state.request = new ApiRequest(new ApiServletContext(servletRequest, servletResponse, null), null);
        state.object = new Object();
        return state;
    }

    private static class RequestState {
        ApiRequest request;
        Object object;
        List<String> setCookies = new ArrayList<>();
    }

    private static class TestManager extends TokenResourceManager {
        Token current;

        @Override
        protected Token listToken() {
            return current;
        }
    }

    private static class FakeAuthTokenDao implements AuthTokenDao {
        AuthToken token;
        int deleteCount;

        FakeAuthTokenDao(AuthToken token) {
            this.token = token;
        }

        @Override
        public AuthToken getTokenByKey(String key) {
            return token != null && key.equals(token.getKey()) ? token : null;
        }

        @Override
        public AuthToken createToken(String jwt, String provider, long accountId, long authenticatedAsAccountId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthToken createToken(String jwt, String provider, long accountId, long authenticatedAsAccountId,
                String clientSessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AuthToken getTokenByAccountId(long accountId) {
            return null;
        }

        @Override
        public String getNewestClientSessionId(long authenticatedAsAccountId, long tokenAccountId) {
            return token == null ? null : token.getClientSessionId();
        }

        @Override
        public void deletePreviousTokens(long authenticatedAsAccountId, long tokenAccountId, String keepKey) {
        }

        @Override
        public int deleteTokensForAccount(long authenticatedAsAccountId) {
            return 0;
        }

        @Override
        public boolean deleteToken(String key) {
            if (token != null && key.equals(token.getKey())) {
                token = null;
                deleteCount++;
                return true;
            }
            return false;
        }
    }
}
