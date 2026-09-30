package io.cattle.platform.servicediscovery.api.filter;

import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.cattle.platform.core.addon.LbConfig;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.dao.ServiceDao;
import io.cattle.platform.core.model.Certificate;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.tables.records.CertificateRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.json.JacksonJsonMapper;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.servicediscovery.api.service.ServiceDiscoveryApiService;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import io.github.ibuildthecloud.gdapi.util.ResponseCodes;
import io.github.ibuildthecloud.gdapi.validation.ValidationErrorCodes;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class LoadBalancerServiceCertificateRemoveFilterTest {
    @Test
    public void v2AlternateReferenceBlocksDeleteAndRemoveEvenWithDifferentDefault() {
        assertBoth(true, false, config(Arrays.asList(7L, 42L), 99L), Collections.emptyList(), null);
    }

    @Test
    public void v2DefaultReferenceBlocksDeleteAndRemove() {
        assertBoth(true, false, config(Arrays.asList(7L), 42L), Collections.emptyList(), null);
    }

    @Test
    public void v2UnreferencedCertificateForwardsDeleteAndRemoveExactlyOnce() {
        assertBoth(false, false, config(Arrays.asList(7L, 8L), 99L), Collections.emptyList(), null);
    }

    @Test
    public void v2MissingConfigurationAndNullOrEmptyListsStillPermitUnreferencedCertificate() {
        assertBoth(false, false, null, Collections.emptyList(), null);
        assertBoth(false, false, config(null, null), Collections.emptyList(), null);
        assertBoth(false, false, config(Collections.emptyList(), null), Collections.emptyList(), null);
        assertBoth(false, false, config(null, 99L), Collections.emptyList(), null);
    }

    @Test
    public void v2NullAlternateListStillProtectsDefaultReference() {
        assertBoth(true, false, config(null, 42L), Collections.emptyList(), null);
    }

    @Test
    public void v1AlternateReferenceStillBlocksDeleteAndRemove() {
        assertBoth(true, true, null, Arrays.asList(certificate(7L), certificate(42L)), certificate(99L));
    }

    @Test
    public void v1DefaultReferenceStillBlocksDeleteAndRemove() {
        assertBoth(true, true, null, Arrays.asList(certificate(7L)), certificate(42L));
    }

    @Test
    public void v1NoReferenceAndMissingDefaultStillPermitDeleteAndRemove() {
        assertBoth(false, true, null, Arrays.asList(certificate(7L)), certificate(99L));
        assertBoth(false, true, null, Collections.emptyList(), null);
    }

    private static void assertBoth(boolean blocked, boolean v1, LbConfig configuration,
            List<Certificate> alternate, Certificate defaultCertificate) {
        for (boolean removeAction : new boolean[] { false, true }) {
            Fixture fixture = new Fixture(v1, configuration, alternate, defaultCertificate, removeAction);
            if (blocked) {
                try {
                    fixture.call();
                    fail("A referenced certificate must not reach the next deletion manager");
                } catch (ClientVisibleException error) {
                    assertEquals(ResponseCodes.METHOD_NOT_ALLOWED, error.getStatus());
                    assertEquals(ValidationErrorCodes.INVALID_ACTION, error.getCode());
                    assertTrue(error.getMessage().contains("test-lb"));
                }
                assertEquals(0, fixture.nextCalls);
            } else {
                assertSame(fixture.result, fixture.call());
                assertEquals(1, fixture.nextCalls);
            }
            assertEquals(1, fixture.loads);
            assertEquals(1, fixture.finds);
            assertEquals(1, fixture.v1Checks);
            assertEquals(v1 ? 1 : 0, fixture.alternateReads);
            assertEquals(v1 ? 1 : 0, fixture.defaultReads);
            assertEquals(configuration, DataUtils.getFields(fixture.service).get(ServiceConstants.FIELD_LB_CONFIG));
            assertEquals("active", fixture.target.getState());
            assertEquals("synthetic-certificate-sentinel", fixture.target.getCert());
            assertEquals("synthetic-key-sentinel", fixture.target.getKey());
        }
    }

    private static LbConfig config(List<Long> alternate, Long defaultId) {
        LbConfig configuration = new LbConfig();
        configuration.setCertificateIds(alternate);
        configuration.setDefaultCertificateId(defaultId);
        return configuration;
    }

    private static CertificateRecord certificate(long number) {
        CertificateRecord certificate = new CertificateRecord();
        certificate.setId(number);
        certificate.setAccountId(2515L);
        certificate.setState("active");
        certificate.setCert("synthetic-certificate-sentinel");
        certificate.setKey("synthetic-key-sentinel");
        return certificate;
    }

    private static final class Fixture {
        final LoadBalancerServiceCertificateRemoveFilter filter = new LoadBalancerServiceCertificateRemoveFilter();
        final CertificateRecord target = certificate(42L);
        final ServiceRecord service = new ServiceRecord();
        final ApiRequest request = new ApiRequest(null, null);
        final Object result = new Object();
        final ResourceManager next;
        final boolean removeAction;
        int nextCalls, loads, finds, v1Checks, alternateReads, defaultReads;

        Fixture(boolean v1, LbConfig configuration, List<Certificate> alternate,
                Certificate defaultCertificate, boolean removeAction) {
            this.removeAction = removeAction;
            service.setId(73L);
            service.setName("test-lb");
            service.setAccountId(2515L);
            service.setKind(ServiceConstants.KIND_LOAD_BALANCER_SERVICE);
            service.setState("active");
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put(ServiceConstants.FIELD_LB_CONFIG, configuration);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DataUtils.FIELDS, fields);
            service.setData(data);
            filter.jsonMapper = new JacksonJsonMapper();
            filter.objectManager = ObjectManager.class.cast(Proxy.newProxyInstance(ObjectManager.class.getClassLoader(),
                    new Class<?>[] { ObjectManager.class }, (proxy, method, arguments) -> {
                        if ("loadResource".equals(method.getName())) {
                            assertSame(Certificate.class, arguments[0]);
                            assertEquals("1c42", arguments[1]);
                            loads++;
                            return target;
                        }
                        assertEquals("find", method.getName());
                        assertSame(Service.class, arguments[0]);
                        assertEquals(3, arguments.length);
                        assertSame(SERVICE.ACCOUNT_ID, arguments[1]);
                        assertArrayEquals(new Object[] { 2515L, SERVICE.REMOVED, null,
                            SERVICE.KIND, ServiceConstants.KIND_LOAD_BALANCER_SERVICE }, (Object[]) arguments[2]);
                        finds++;
                        return Arrays.asList(service);
                    }));
            filter.sdService = ServiceDiscoveryApiService.class.cast(Proxy.newProxyInstance(
                    ServiceDiscoveryApiService.class.getClassLoader(), new Class<?>[] { ServiceDiscoveryApiService.class },
                    (proxy, method, arguments) -> {
                        assertEquals("isV1LB", method.getName());
                        assertSame(service, arguments[0]);
                        v1Checks++;
                        return v1;
                    }));
            filter.svcDao = ServiceDao.class.cast(Proxy.newProxyInstance(ServiceDao.class.getClassLoader(),
                    new Class<?>[] { ServiceDao.class }, (proxy, method, arguments) -> {
                        assertSame(service, arguments[0]);
                        if ("getLoadBalancerServiceCertificates".equals(method.getName())) {
                            alternateReads++;
                            return alternate;
                        }
                        assertEquals("getLoadBalancerServiceDefaultCertificate", method.getName());
                        defaultReads++;
                        return defaultCertificate;
                    }));
            request.setId("1c42");
            request.setAction("ReMoVe");
            next = ResourceManager.class.cast(Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),
                    new Class<?>[] { ResourceManager.class }, (proxy, method, arguments) -> {
                        assertEquals(removeAction ? "resourceAction" : "delete", method.getName());
                        assertEquals("certificate", arguments[0]);
                        if (!removeAction) assertEquals("1c42", arguments[1]);
                        assertSame(request, arguments[arguments.length - 1]);
                        nextCalls++;
                        return result;
                    }));
        }

        Object call() {
            return removeAction ? filter.resourceAction("certificate", request, next)
                    : filter.delete("certificate", "1c42", request, next);
        }
    }
}
