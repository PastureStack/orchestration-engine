package io.cattle.platform.api.auth;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Provenance supplied only after the normal JWT provider verified this exact Cookie token. */
public final class ApiCookieAuthentication {
    private static final String ATTRIBUTE = ApiCookieAuthentication.class.getName();
    private record Verified(byte[] digest) { }
    private ApiCookieAuthentication() { }

    public static void verified(ApiRequest request, String selectedJwt) {
        String cookie = cookie(request);
        if (cookie != null && cookie.equals(selectedJwt)) request.setAttribute(ATTRIBUTE, new Verified(hash(cookie)));
    }

    public static boolean isVerified(ApiRequest request) {
        String cookie = cookie(request);
        return cookie != null && request.getAttribute(ATTRIBUTE) instanceof Verified verified
                && MessageDigest.isEqual(verified.digest(), hash(cookie));
    }

    private static String cookie(ApiRequest request) {
        if (request == null || request.getServletContext() == null) return null;
        var cookies = request.getServletContext().getRequest().getCookies();
        if (cookies == null) return null;
        for (var cookie : cookies) if ("token".equalsIgnoreCase(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank())
            return cookie.getValue();
        return null;
    }
    private static byte[] hash(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException unavailable) { throw new IllegalStateException("Cookie provenance unavailable"); }
    }
}
