package io.cattle.platform.iaas.api.auth.change;

import static org.junit.Assert.*;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.util.DataAccessor;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AiExternalServiceReviewChangeTest {
    @Test public void hostnameToIpsShowsTheExistingHostnameBeingCleared() {
        ServiceRecord service = service("localhost", null);
        var changes = changes(service, Map.of("externalIpAddresses", List.of("127.0.0.1")));
        assertChange(changes, "hostname", "localhost", null);
        assertChange(changes, "externalIpAddresses", null, "[\"127.0.0.1\"]");
    }

    @Test public void ipsToHostnameShowsTheExistingAddressesBeingCleared() {
        ServiceRecord service = service(null, List.of("192.0.2.4", "2001:db8::4"));
        var changes = changes(service, Map.of("hostname", "db.example.test"));
        assertChange(changes, "externalIpAddresses", "[\"192.0.2.4\",\"2001:db8::4\"]", null);
        assertChange(changes, "hostname", null, "db.example.test");
    }

    @Test public void ipsToIpsShowsThePreviousAddressList() {
        ServiceRecord service = service(null, List.of("192.0.2.4"));
        var changes = changes(service, Map.of("externalIpAddresses", List.of("192.0.2.8")));
        assertChange(changes, "hostname", null, null);
        assertChange(changes, "externalIpAddresses", "[\"192.0.2.4\"]", "[\"192.0.2.8\"]");
    }

    @Test public void hostnameToHostnameShowsThePreviousHostname() {
        ServiceRecord service = service("old.example.test", null);
        var changes = changes(service, Map.of("hostname", "new.example.test"));
        assertChange(changes, "externalIpAddresses", null, null);
        assertChange(changes, "hostname", "old.example.test", "new.example.test");
    }

    @Test public void nullAndEmptyDynamicFieldsKeepTheExistingUnsetDisplay() {
        for (String hostname : Arrays.asList(null, "")) {
            for (Object ips : Arrays.asList(null, List.of())) {
                ServiceRecord service = service(hostname, ips);
                var toIps = changes(service, Map.of("externalIpAddresses", List.of("192.0.2.8")));
                assertChange(toIps, "hostname", null, null);
                assertChange(toIps, "externalIpAddresses", null, "[\"192.0.2.8\"]");
                var toHostname = changes(service, Map.of("hostname", "db.example.test"));
                assertChange(toHostname, "externalIpAddresses", null, null);
                assertChange(toHostname, "hostname", null, "db.example.test");
            }
        }
    }

    @Test public void unsafeExistingDynamicTargetsRemainRedacted() {
        var hostname = changes(service("https://user:PRIVATE_VALUE@db.test/path", null),
                Map.of("externalIpAddresses", List.of("192.0.2.8")));
        assertChange(hostname, "hostname", null, null);
        assertFalse(hostname.toString().contains("PRIVATE_VALUE"));
        var ips = changes(service(null, List.of("PRIVATE_VALUE")), Map.of("hostname", "db.example.test"));
        assertChange(ips, "externalIpAddresses", null, null);
        assertFalse(ips.toString().contains("PRIVATE_VALUE"));
    }

    @Test public void reviewReadsDoNotMutateNativeTargetsOrProjectOtherConfiguration() throws Exception {
        ServiceRecord service = service("localhost", null);
        DataAccessor.fields(service).withKey("unrelatedSetting").set("PRIVATE_VALUE");
        JacksonJsonMapper json = new JacksonJsonMapper();
        String before = json.writeValueAsString(service.getData());
        var changes = changes(service, Map.of("externalIpAddresses", List.of("127.0.0.1")));
        assertEquals(before, json.writeValueAsString(service.getData()));
        assertEquals("localhost", DataAccessor.fieldString(service, "hostname"));
        assertNull(DataAccessor.field(service, "externalIpAddresses", Object.class));
        assertFalse(changes.toString().contains("PRIVATE_VALUE"));
        assertEquals(2, changes.size());
    }

    private static ServiceRecord service(String hostname, Object ips) {
        ServiceRecord service = new ServiceRecord();
        service.setId(7L); service.setAccountId(5L); service.setKind("externalService");
        service.setName("own-external"); service.setState("active");
        DataAccessor.fields(service).withKey("hostname").set(hostname);
        DataAccessor.fields(service).withKey("externalIpAddresses").set(ips);
        return service;
    }

    private static List<Map<String, Object>> changes(ServiceRecord service, Map<String, Object> parameters) {
        AiResourceChangeDomain domain = new AiResourceChangeDomain();
        domain.json = new JacksonJsonMapper();
        return domain.changes(AiResourceChangeDescriptor.require("externalService", "update_targets"), service, parameters);
    }

    private static void assertChange(List<Map<String, Object>> changes, String field, Object before, Object after) {
        var rows = changes.stream().filter(row -> field.equals(row.get("field"))).toList();
        assertEquals("One exact review field: " + field, 1, rows.size());
        assertEquals(field + " before", before, rows.get(0).get("before"));
        assertEquals(field + " after", after, rows.get(0).get("after"));
    }
}
