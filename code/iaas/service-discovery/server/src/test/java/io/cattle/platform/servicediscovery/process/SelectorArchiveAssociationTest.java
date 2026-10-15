package io.cattle.platform.servicediscovery.process;

import static org.junit.Assert.*;
import io.cattle.platform.core.dao.InstanceDao;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.servicediscovery.api.dao.ServiceExposeMapDao;
import io.cattle.platform.servicediscovery.api.service.ServiceInstanceAssociationGuard;
import io.cattle.platform.servicediscovery.api.service.ArchivedAssociationException;
import io.cattle.platform.servicediscovery.service.ServiceDiscoveryService;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.Test;

public class SelectorArchiveAssociationTest {
    @Test public void bothSelectorEntrypointsFinishWithoutNewMapsWhenAlreadyArchivedOrArchiveWinsLate() {
        for(boolean serviceSide:List.of(true,false))for(boolean early:List.of(true,false)) {
            Fixture f=new Fixture(early,true,false);f.run(serviceSide);
            assertEquals(0,f.scheduled.get());assertEquals(early?0:1,f.attempted.get());assertEquals("running",f.instance.getState());
        }
    }

    @Test public void ordinarySelectorRegistrationStillCreatesAndSchedulesMaps() {
        for(boolean serviceSide:List.of(true,false)) {
            Fixture f=new Fixture(false,false,false);f.run(serviceSide);
            assertEquals(1,f.attempted.get());assertEquals(1,f.scheduled.get());
        }
    }

    @Test public void unrelatedStorageFailuresAreNotSwallowed() {
        for(boolean serviceSide:List.of(true,false)) {
            Fixture f=new Fixture(false,false,true);
            try{f.run(serviceSide);fail("storage failure must propagate");}catch(IllegalStateException expected){assertEquals("storage",expected.getMessage());}
        }
    }

    private static <T>T proxy(Class<T> type,InvocationHandler handler){return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler));}
    private static class Fixture {
        final ServiceRecord service=new ServiceRecord();final InstanceRecord instance=new InstanceRecord();
        final AtomicInteger attempted=new AtomicInteger(),scheduled=new AtomicInteger();
        final ObjectManager objects;final ObjectProcessManager processes;final ServiceDiscoveryService discovery;
        final ServiceExposeMapDao maps;final LockManager locks;final InstanceDao instanceDao;final List<ServiceInstanceAssociationGuard> guards;
        Fixture(boolean early,boolean late,boolean storageFailure){
            service.setId(2L);service.setAccountId(1L);service.setSelectorContainer("app=test");service.setState("inactive");
            instance.setId(3L);instance.setAccountId(1L);instance.setState("running");instance.setData(Map.of());
            objects=proxy(ObjectManager.class,(p,m,a)->{if(m.getName().equals("find"))return List.of(service);throw new UnsupportedOperationException(m.getName());});
            processes=proxy(ObjectProcessManager.class,(p,m,a)->{scheduled.incrementAndGet();return null;});
            discovery=proxy(ServiceDiscoveryService.class,(p,m,a)->{if(m.getName().equals("isSelectorContainerMatch"))return true;return null;});
            locks=proxy(LockManager.class,(p,m,a)->((LockCallback<?>)a[1]).doWithLock());
            instanceDao=proxy(InstanceDao.class,(p,m,a)->{
                if(m.getName().equals("getInstanceIdsForAccount"))return List.of(3L);
                if(m.getName().equals("getCacheInstanceData"))return Map.of();throw new UnsupportedOperationException(m.getName());
            });
            maps=proxy(ServiceExposeMapDao.class,(p,m,a)->{
                if(m.getName().equals("getUnmanagedServiceInstanceMapsToRemove"))return List.of();
                if(m.getName().equals("createServiceInstanceMap")){
                    attempted.incrementAndGet();if(storageFailure)throw new IllegalStateException("storage");if(late)throw new ArchivedAssociationException();
                    ServiceExposeMapRecord map=new ServiceExposeMapRecord();map.setState("requested");return map;
                }throw new UnsupportedOperationException(m.getName());
            });
            guards=List.of(new ServiceInstanceAssociationGuard(){
                @Override public ServiceExposeMap associate(Service s,Instance i,Supplier<ServiceExposeMap> next){return next.get();}
                @Override public boolean blocksNewAssociations(Service s){return early;}
            });
        }
        void run(boolean serviceSide){
            ProcessState state=proxy(ProcessState.class,(p,m,a)->{if(m.getName().equals("getResource"))return serviceSide?service:instance;if(m.getName().equals("getData"))return Map.of();throw new UnsupportedOperationException(m.getName());});
            ProcessInstance process=proxy(ProcessInstance.class,(p,m,a)->m.getName().equals("getName")?"service.update":null);
            if(serviceSide){
                SelectorServiceCreatePostListener listener=new SelectorServiceCreatePostListener();
                listener.setObjectManager(objects);listener.setObjectProcessManager(processes);listener.sdService=discovery;listener.exposeMapDao=maps;
                listener.lockManager=locks;listener.instanceDao=instanceDao;listener.associationGuards=guards;listener.handle(state,process);
            }else{
                SelectorInstancePostListener listener=new SelectorInstancePostListener();listener.setObjectManager(objects);listener.setObjectProcessManager(processes);
                listener.sdService=discovery;listener.exposeMapDao=maps;listener.lockManager=locks;listener.associationGuards=guards;listener.handle(state,process);
            }
        }
    }
}
