package io.cattle.platform.api.servlet;

interface SecurityHeaderSettings {

    boolean enabled();

    String frameOptions();

    String contentSecurityPolicy();

    String contentTypeOptions();

    String referrerPolicy();

    boolean hstsEnabled();

    String hsts();

}
