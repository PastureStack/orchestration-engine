package io.cattle.platform.iaas.api.auditing;

import static org.junit.Assert.assertEquals;
import io.github.ibuildthecloud.gdapi.json.JacksonMapper;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class AuditServiceImplTest {

    @Test
    public void auditObjectMapUsesCheckedStringKeys() {
        AuditServiceImpl service = service();
        Map<Object, Object> input = new HashMap<Object, Object>();
        input.put("name", "service-a");

        Map<String, Object> result = service.auditObjectMap(input);

        assertEquals("service-a", result.get("name"));
    }

    @Test
    public void auditObjectMapPreservesJacksonKeyNormalization() {
        AuditServiceImpl service = service();
        Map<Object, Object> input = new HashMap<Object, Object>();
        input.put(Integer.valueOf(1), "service-a");

        Map<String, Object> result = service.auditObjectMap(input);

        assertEquals("service-a", result.get("1"));
    }

    protected AuditServiceImpl service() {
        JacksonMapper mapper = new JacksonMapper();
        mapper.init();

        AuditServiceImpl service = new AuditServiceImpl();
        service.jsonMapper = mapper;
        return service;
    }
}
