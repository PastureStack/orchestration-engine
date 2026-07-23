package io.github.ibuildthecloud.gdapi.request.handler;

import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.model.Schema.Method;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.util.RequestUtils;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;

import java.io.IOException;
import java.security.SecureRandom;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CSRFCookieHandler extends AbstractApiRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(CSRFCookieHandler.class);

    private static final SecureRandom RANDOM = new SecureRandom();

    public static final String CSRF = "CSRF";
    public static final String HEADER = "X-API-CSRF";
    static final int TOKEN_BYTES = 32;
    static final String SAME_SITE = "Lax";

    @Override
    public void handle(ApiRequest request) throws IOException {
        HttpServletRequest httpRequest = request.getServletContext().getRequest();
        HttpServletResponse response = request.getServletContext().getResponse();

        if (!RequestUtils.isBrowser(httpRequest, false)) {
            return;
        }

        Cookie csrf = null;
        Cookie[] cookies = httpRequest.getCookies();

        if (cookies != null) {
            for (Cookie c : httpRequest.getCookies()) {
                if (CSRF.equals(c.getName()) && c.getName() != null) {
                    csrf = c;
                    break;
                }
            }
        }

        if (csrf == null) {
            byte[] bytes = new byte[TOKEN_BYTES];
            RANDOM.nextBytes(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02X", b));
            }
            csrf = new Cookie(CSRF, sb.toString());
        } else if (!Method.GET.isMethod(request.getMethod())) {
            /*
             * Very important to use request.getMethod() and not httpRequest.getMethod(). The client can override the HTTP method with _method
             */
            if (csrf.getValue().equals(httpRequest.getHeader(HEADER))) {
                // Good
            } else if (csrf.getValue().equals(httpRequest.getParameter(CSRF))) {
                // Good
            } else {
                log.warn("Request's CSRF header did not match cookie");
                throw new ClientVisibleException(ResponseCodes.FORBIDDEN, ValidationErrorCodes.INVALID_CSRF_TOKEN);
            }
        }

        csrf.setPath("/");
        csrf.setSecure(isSecureRequest(httpRequest));
        csrf.setAttribute("SameSite", SAME_SITE);
        response.addCookie(csrf);
    }

    protected boolean isSecureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }

        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null) {
            for (String value : forwardedProto.split(",")) {
                if ("https".equalsIgnoreCase(value.trim())) {
                    return true;
                }
            }
        }

        String forwarded = request.getHeader("Forwarded");
        if (forwarded != null) {
            for (String part : forwarded.split(";")) {
                String value = part.trim();
                if (value.regionMatches(true, 0, "proto=https", 0, "proto=https".length())) {
                    return true;
                }
            }
        }

        return false;
    }

}
