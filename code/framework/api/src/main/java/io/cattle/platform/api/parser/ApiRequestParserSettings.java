package io.cattle.platform.api.parser;

import java.util.List;

interface ApiRequestParserSettings {

    boolean allowClientOverrideHeaders();

    List<String> httpsPorts();

    boolean trustForwardedHost();

    List<String> allowedForwardedHosts();

    String apiHost();
}
