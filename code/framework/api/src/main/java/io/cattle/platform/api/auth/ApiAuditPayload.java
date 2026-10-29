package io.cattle.platform.api.auth;

import io.github.ibuildthecloud.gdapi.request.ApiRequest;

/** A server-created omission marker. Request bodies and headers cannot manufacture this type. */
public final class ApiAuditPayload {
    private static final String ATTRIBUTE = ApiAuditPayload.class.getName();
    private enum Omission { VERIFIED_CATALOG_LAUNCH }
    private ApiAuditPayload() { }
    public static void verifiedCatalogLaunch(ApiRequest request) { request.setAttribute(ATTRIBUTE, Omission.VERIFIED_CATALOG_LAUNCH); }
    public static boolean omitted(ApiRequest request) { return request != null && request.getAttribute(ATTRIBUTE) == Omission.VERIFIED_CATALOG_LAUNCH; }
}
