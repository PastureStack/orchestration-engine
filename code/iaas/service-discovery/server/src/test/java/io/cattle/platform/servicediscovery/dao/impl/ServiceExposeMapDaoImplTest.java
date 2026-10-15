package io.cattle.platform.servicediscovery.dao.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.servicediscovery.api.service.ServiceInstanceAssociationGuard;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class ServiceExposeMapDaoImplTest {

    @Test public void onlyNewAssociationsPassThroughTheDomainGuard() {
        ServiceExposeMapDaoImpl dao=new ServiceExposeMapDaoImpl();AtomicInteger writes=new AtomicInteger(),guards=new AtomicInteger();
        ServiceRecord service=new ServiceRecord();service.setId(2L);service.setAccountId(1L);
        InstanceRecord instance=new InstanceRecord();instance.setId(3L);
        ServiceExposeMapRecord existing=new ServiceExposeMapRecord();ServiceExposeMap[] found={existing};
        dao.objectManager=(ObjectManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ObjectManager.class},(p,m,a)->{
            if(m.getName().equals("findAny"))return found[0];
            if(m.getName().equals("create")){writes.incrementAndGet();return existing;}
            throw new UnsupportedOperationException(m.getName());
        });
        dao.associationGuards=List.of(new ServiceInstanceAssociationGuard(){
            @Override public ServiceExposeMap associate(Service s,Instance i,Supplier<ServiceExposeMap> next){guards.incrementAndGet();throw new IllegalStateException("archived");}
            @Override public boolean blocksNewAssociations(Service s){return true;}
        });
        assertSame(existing,dao.createServiceInstanceMap(service,instance,false));assertEquals(0,guards.get());
        found[0]=null;
        try{dao.createServiceInstanceMap(service,instance,false);fail("new association must be guarded");}catch(IllegalStateException expected){assertEquals("archived",expected.getMessage());}
        assertEquals(1,guards.get());assertEquals(0,writes.get());
    }

    @Test
    public void instanceLabelsReturnsEmptyMapWhenMissing() {
        assertTrue(ServiceExposeMapDaoImpl.instanceLabels(null).isEmpty());
    }

    @Test
    public void instanceLabelsCopiesStringEntriesInOrder() {
        Map<String, String> labels = new LinkedHashMap<String, String>();
        labels.put("io.rancher.service.launch.config", "sidekick");
        labels.put("io.rancher.deployment.unit", "du-1");

        Map<String, String> result = ServiceExposeMapDaoImpl.instanceLabels(labels);

        assertEquals(labels, result);
        assertEquals(new ArrayList<String>(labels.keySet()), new ArrayList<String>(result.keySet()));
    }

    @Test(expected = ClassCastException.class)
    public void instanceLabelsRejectsNonStringKeys() {
        Map<Object, String> labels = new LinkedHashMap<Object, String>();
        labels.put(42, "sidekick");

        ServiceExposeMapDaoImpl.instanceLabels(labels);
    }

    @Test(expected = ClassCastException.class)
    public void instanceLabelsRejectsNonStringValues() {
        Map<String, Object> labels = new LinkedHashMap<String, Object>();
        labels.put("io.rancher.service.launch.config", Boolean.TRUE);

        ServiceExposeMapDaoImpl.instanceLabels(labels);
    }
}
