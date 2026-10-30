package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiExternalServiceChangeTest {
    @Test public void createUsesNativeFieldsWithoutImageOrImplicitActivation() {
        var descriptor = AiResourceChangeDescriptor.require("externalService", "create");
        var parameters = descriptor.parameters(Map.of("name", "orders-db", "stackId", "1st8", "hostname", "db.example.test"));
        var body = descriptor.body(parameters);
        assertEquals("externalService", body.get("type")); assertEquals(false, body.get("startOnCreate"));
        assertEquals(Map.of(), body.get("launchConfig")); assertNull(body.get("externalIpAddresses"));
        assertFalse(body.containsKey("image")); assertFalse(body.containsKey("imageUuid"));
        assertTrue(descriptor.requiresStackParent()); assertEquals("create", descriptor.keyOperation());
        assertEquals("update", AiResourceChangeDescriptor.require("externalService", "start").keyOperation());
        assertEquals("delete", AiResourceChangeDescriptor.require("externalService", "remove").keyOperation());
    }

    @Test public void targetModesAreExclusiveAndClearingNeverPreservesThePreviousMode() {
        var descriptor = AiResourceChangeDescriptor.require("externalService", "update_targets");
        var ips = List.of("192.0.2.4", "2001:db8::4");
        assertEquals(ips, descriptor.body(descriptor.parameters(Map.of("externalIpAddresses", ips))).get("externalIpAddresses"));
        assertNull(descriptor.body(descriptor.parameters(Map.of("externalIpAddresses", ips))).get("hostname"));
        assertNull(descriptor.body(descriptor.parameters(Map.of("hostname", "db.example.test"))).get("externalIpAddresses"));
        for (Map<String, Object> invalid : List.of(Map.<String, Object>of(), Map.<String, Object>of("hostname", "db", "externalIpAddresses", ips),
                Map.<String, Object>of("externalIpAddresses", List.of()), Map.<String, Object>of("externalIpAddresses", List.of("192.0.2.4", "192.0.2.4"))))
            assertThrows(ClientVisibleException.class, () -> descriptor.parameters(invalid));
    }

    @Test public void onlyBoundedDnsAndNumericIpSyntaxIsAccepted() {
        for (String host : List.of("db", "db.example.test", "DB-2.example.test.", "xn--fiqs8s.example"))
            assertTrue(host, AiExternalServiceChange.hostname(host));
        for (String host : List.of("https://db.test", "user:secret@db.test", "db:443", "db/path", "db?q=secret",
                "*.db", "a..b", "-db", "db_1", "a\nb", "a".repeat(64)))
            assertFalse(host, AiExternalServiceChange.hostname(host));
        for (String ip : List.of("0.0.0.0", "255.255.255.255", "::1", "2001:db8::1", "::ffff:192.0.2.1"))
            assertTrue(ip, AiExternalServiceChange.address(ip));
        for (String ip : List.of("db.test", "127.1", "256.1.1.1", "001.2.3.4", "192.0.2.1:80", "[::1]", "fe80::1%eth0", "https://127.0.0.1", "1:2:3"))
            assertFalse(ip, AiExternalServiceChange.address(ip));
    }

    @Test public void targetsDoNotProvideTransportOrSecretAuthority() {
        var descriptor = AiResourceChangeDescriptor.require("externalService", "create");
        for (String forbidden : List.of("url", "method", "headers", "accountId", "startOnCreate", "launchConfig", "healthCheck", "environment", "secret")) {
            var input = new LinkedHashMap<String, Object>(Map.of("name", "external", "stackId", "1st8", "hostname", "db.test"));
            input.put(forbidden, "PRIVATE_VALUE");
            assertThrows(forbidden, ClientVisibleException.class, () -> descriptor.parameters(input));
        }
        for (String operation : List.of("restart", "scale", "archive", "restore"))
            assertThrows(ClientVisibleException.class, () -> AiResourceChangeDescriptor.require("externalService", operation));
    }

    @Test public void completionRequiresTheActualEndpointMapNotOnlyRootActive() {
        var service = service(); var map = map();
        service.setState("inactive"); assertTrue(AiExternalServiceChange.complete("create", service,
                Map.of("hostname", "db.test"), List.of()));
        service.setState("active"); assertFalse(AiExternalServiceChange.complete("start", service, Map.of(), List.of()));
        map.setState("activating"); assertFalse(AiExternalServiceChange.complete("start", service, Map.of(), List.of(map)));
        map.setState("active"); assertTrue(AiExternalServiceChange.complete("start", service, Map.of(), List.of(map)));
        map.setHostName("different.test"); assertFalse(AiExternalServiceChange.complete("start", service, Map.of(), List.of(map)));
        map.setHostName("db.test"); service.setState("inactive");
        assertFalse(AiExternalServiceChange.complete("stop", service, Map.of(), List.of(map)));
        map.setState("removed"); map.setRemoved(new Date(1));
        assertTrue(AiExternalServiceChange.complete("stop", service, Map.of(), List.of(map)));
        service.setState("removed"); service.setRemoved(new Date(2));
        assertTrue(AiExternalServiceChange.complete("remove", service, Map.of(), List.of(map)));
        map.setRemoved(null); map.setState("removing");
        assertFalse(AiExternalServiceChange.complete("remove", service, Map.of(), List.of(map)));
    }

    @Test public void foreignManagedInstanceOrDuplicateMapsCannotComplete() {
        var service = service(); var map = map();
        for (String mutation : List.of("service", "account", "instance", "unmanaged", "upgrade", "duplicate")) {
            map = map();
            switch (mutation) {
            case "service" -> map.setServiceId(99L);
            case "account" -> map.setAccountId(99L);
            case "instance" -> map.setInstanceId(99L);
            case "unmanaged" -> map.setManaged(false);
            case "upgrade" -> map.setUpgrade(true);
            default -> { }
            }
            assertFalse(mutation, AiExternalServiceChange.complete("start", service, Map.of(),
                    mutation.equals("duplicate") ? List.of(map, map()) : List.of(map)));
        }
    }

    @Test public void changedTargetsWaitForOldMapRetirementAndNewMapConvergence() {
        var service = service(); var old = map();
        DataAccessor.fields(service).withKey("hostname").set(null);
        DataAccessor.fields(service).withKey("externalIpAddresses").set(List.of("192.0.2.8"));
        var current = map(); current.setHostName(null); current.setIpAddress("192.0.2.8");
        Map<String, Object> parameters = Map.of("externalIpAddresses", List.of("192.0.2.8"));
        assertFalse(AiExternalServiceChange.complete("update_targets", service, parameters, List.of(old, current)));
        old.setRemoved(new Date(1)); old.setState("removed");
        assertTrue(AiExternalServiceChange.complete("update_targets", service, parameters, List.of(old, current)));
    }

    @Test public void safeReadDoesNotCopyRawConfigurationOrUnsafeExistingTargets() {
        assertEquals(Map.of("externalIpAddresses", "[\"192.0.2.1\",\"::1\"]"),
                withoutNulls(AiExternalServiceChange.summary(Map.of("externalIpAddresses", List.of("192.0.2.1", "::1"), "password", "PRIVATE"))));
        assertTrue(AiExternalServiceChange.summary(Map.of("hostname", "https://user:PRIVATE@db.test/path")).isEmpty());
        assertTrue(AiExternalServiceChange.summary(Map.of("externalIpAddresses", List.of("PRIVATE"))).isEmpty());
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> source) {
        var result = new LinkedHashMap<>(source); result.values().removeIf(java.util.Objects::isNull); return result;
    }
    private static ServiceRecord service() {
        var service = new ServiceRecord(); service.setId(2L); service.setUuid("external-service"); service.setKind("externalService");
        service.setAccountId(5L); service.setStackId(8L); service.setState("active");
        DataAccessor.fields(service).withKey("hostname").set("db.test"); return service;
    }
    private static ServiceExposeMapRecord map() {
        var map = new ServiceExposeMapRecord(); map.setId(3L); map.setUuid("endpoint-map"); map.setServiceId(2L);
        map.setAccountId(5L); map.setManaged(true); map.setUpgrade(false); map.setHostName("db.test"); map.setState("active"); return map;
    }
}
