package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.*;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import io.github.ibuildthecloud.gdapi.request.ApiRequest;
import io.github.ibuildthecloud.gdapi.request.resource.ResourceManager;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import io.cattle.platform.process.common.generic.GenericResourceProcessDefinition;
import io.cattle.platform.engine.process.LaunchConfiguration;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.context.annotation.AnnotationConfigUtils;
import org.springframework.beans.factory.xml.XmlBeanDefinitionReader;
import org.springframework.core.io.ByteArrayResource;
import org.jooq.impl.DSL;
import org.jooq.SQLDialect;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ArchiveValidationFilterTest {
    @Test public void everyExistingServiceSubtypeAndV1AliasUsesTheArchiveFilter() {
        var types=new java.util.HashSet<>(List.of(new ArchiveValidationFilter().getTypes()));
        assertTrue(types.containsAll(List.of(new ServiceValidationFilter().getTypes())));
        assertTrue(types.containsAll(List.of("environment","stack","container","instance","composeProject","kubernetesStack")));
        assertEquals(types.size(),new ArchiveValidationFilter().getTypes().length);
    }
    @Test public void stoppedServiceAndEmptyStackArchiveAndRestoreWithoutLifecycleWork() {
        Fixture f = new Fixture();
        f.update(f.service, true); assertTrue(ArchiveValidationFilter.archived(f.service));
        f.update(f.service, false); assertFalse(ArchiveValidationFilter.archived(f.service));
        f.update(f.stack, true); assertTrue(ArchiveValidationFilter.archived(f.stack));
        assertEquals("inactive", f.service.getState());
        assertEquals("active", f.stack.getState());
        assertEquals(3, f.writes.get());
    }

    @Test public void activeZeroScaleGlobalAndAllUncertainStatesCannotArchive() {
        for (String state : List.of("active", "activating", "deactivating", "upgrading", "error", "updating-inactive")) {
            Fixture f = new Fixture(); f.service.setState(state);
            DataAccessor.setField(f.service, "scale", 0);
            DataAccessor.setField(f.service, "launchConfig", Map.of("labels", Map.of("io.rancher.scheduler.global", "true")));
            assertCode("ArchiveRequiresStopped", () -> f.update(f.service, true));
            assertEquals(0, f.writes.get()); assertFalse(ArchiveValidationFilter.archived(f.service));
        }
    }

    @Test public void runningOrTransitioningOldSidekicksAndUpgradeInstancesBlockArchive() {
        for (String state : List.of("running", "starting", "stopping", "error", "purging")) {
            Fixture f = new Fixture();
            InstanceRecord sidekick = instance(9, state);
            DataAccessor.setField(sidekick, "labels", Map.of("io.rancher.service.launch.config", "old-sidekick"));
            f.related.add(sidekick);
            assertCode("ArchiveRequiresStopped", () -> f.update(f.service, true));
            assertCode("ArchiveRequiresStopped", () -> f.update(f.stack, true));
        }
    }

    @Test public void stoppedInstancesAndTerminalInstancesDoNotBlock() {
        Fixture f = new Fixture();
        f.related.add(instance(7, "stopped")); f.related.add(instance(8, "removed")); f.related.add(instance(9, "purged"));
        f.update(f.service, true);
        assertTrue(ArchiveValidationFilter.archived(f.service));
    }

    @Test public void parentArchiveRequiresAllServicesInactiveAndPreservesChildFlags() {
        Fixture f = new Fixture(); f.children.add(f.service);
        f.service.setState("active");
        assertCode("ArchiveRequiresStopped", () -> f.update(f.stack, true));
        f.service.setState("inactive"); f.update(f.service, true);
        f.update(f.stack, true); f.update(f.stack, false);
        assertTrue(ArchiveValidationFilter.archived(f.service));
    }

    @Test public void systemAndInfraRejectedEvenForAnOtherwiseAuthorizedCallerButRestoreWorks() {
        for (String kind : List.of("service", "loadBalancerService", "externalService", "dnsService", "networkDriverService", "storageDriverService")) {
            Fixture f = new Fixture(); f.service.setKind(kind);
            DataAccessor.setField(f.service, "system", true);
            assertCode("ArchiveSystemResource", () -> f.update(f.service, true));
            DataAccessor.setField(f.service, "archived", true);
            f.update(f.service, false); assertFalse(ArchiveValidationFilter.archived(f.service));
        }
        Fixture f = new Fixture(); f.stack.setSystem(true);
        assertCode("ArchiveSystemResource", () -> f.update(f.stack, true));
        assertCode("ArchiveSystemResource", () -> f.update(f.service, true));
    }

    @Test public void createTrueAndChildrenOfArchivedParentsRejectedBeforeWriting() {
        Fixture f = new Fixture();
        assertCode("ArchiveCreateUnsupported", () -> f.filter.create("service", request(Map.of("archived", true)), f.next(f.service)));
        DataAccessor.setField(f.stack, "archived", true);
        assertCode("ResourceArchived", () -> f.filter.create("service", request(Map.of("stackId", 1L, "startOnCreate", true)), f.next(f.service)));
        assertEquals(0, f.writes.get());
    }

    @Test public void mixedChangesAreAtomicAndOrdinaryFullDtosKeepWorking() {
        Fixture f = new Fixture();
        assertCode("ArchiveMixedUpdate", () -> f.filter.update("service", "2", request(Map.of("archived", true, "name", "other")), f.next(f.service)));
        assertEquals("svc", f.service.getName()); assertEquals(0, f.writes.get());
        f.filter.update("service", "2", request(Map.of("archived", false, "name", "other")), f.next(f.service));
        assertEquals(1, f.writes.get()); assertEquals("other", f.service.getName());
    }

    @Test public void unauthorizedTargetCannotWriteOrLeakAnArchiveSpecificDenial() {
        Fixture f = new Fixture(); f.service.setState("active");
        ResourceManager denied = (ResourceManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResourceManager.class}, (p,m,a) -> {
            throw new ClientVisibleException(403, "OriginalPermissionDenied");
        });
        assertCode("OriginalPermissionDenied", () -> f.filter.update("service", "2", request(Map.of("archived", true)), denied));
        assertEquals(0, f.writes.get()); assertFalse(ArchiveValidationFilter.archived(f.service));
    }

    @Test public void archivedSelfOrParentPreventsStartButStopDeleteAndHealthKeepExistingBehavior() {
        Fixture f = new Fixture(); AtomicInteger effects = new AtomicInteger();
        DataAccessor.setField(f.service, "archived", true);
        assertCode("ResourceArchived", () -> f.filter.transition(f.service, "state", "activating", () -> {effects.incrementAndGet(); return true;}));
        assertCode("ResourceArchived", () -> f.filter.transition(instance(8, "stopped"), "state", "starting", () -> true));
        DataAccessor.setField(f.service, "archived", false); DataAccessor.setField(f.stack, "archived", true);
        assertCode("ResourceArchived", () -> f.filter.transition(f.service, "state", "activating", () -> true));
        assertTrue(f.filter.transition(f.service, "state", "removing", () -> true));
        assertTrue(f.filter.transition(instance(8, "stopped"), "state", "stopping", () -> true));
        assertTrue(f.filter.transition(f.service, "healthState", "healthy", () -> true));
        assertEquals(0, effects.get());
    }

    @Test public void movingIntoOrOutOfArchivedParentCannotBypassRestore() {
        Fixture f = new Fixture(); StackRecord other = stack(3); f.stacks.put(3L, other);
        DataAccessor.setField(other, "archived", true);
        assertCode("ResourceArchived", () -> f.filter.update("service", "2", request(Map.of("stackId", 3L)), f.next(f.service)));
        DataAccessor.setField(other, "archived", false); DataAccessor.setField(f.stack, "archived", true);
        assertCode("ResourceArchived", () -> f.filter.update("service", "2", request(Map.of("stackId", 3L)), f.next(f.service)));
        assertEquals(Long.valueOf(1), f.service.getStackId()); assertEquals(0, f.writes.get());
    }

    @Test public void archiveWinsRaceSoActivationHasNoEffects() throws Exception {
        Fixture f = new Fixture(); CountDownLatch writing = new CountDownLatch(1), resume = new CountDownLatch(1);
        f.beforeWrite = () -> {writing.countDown(); await(resume);};
        var pool = Executors.newFixedThreadPool(2);
        try {
            var archive = pool.submit(() -> f.update(f.service, true));
            assertTrue(writing.await(3, TimeUnit.SECONDS));
            var activate = pool.submit(() -> assertCode("ResourceArchived", () -> f.filter.transition(f.service, "state", "activating", () -> {f.service.setState("activating"); return true;})));
            resume.countDown(); archive.get(3, TimeUnit.SECONDS); activate.get(3, TimeUnit.SECONDS);
            assertEquals("inactive", f.service.getState()); assertTrue(ArchiveValidationFilter.archived(f.service));
        } finally {resume.countDown(); pool.shutdownNow();}
    }

    @Test public void activationWinsRaceSoArchiveCannotWrite() throws Exception {
        Fixture f = new Fixture(); CountDownLatch activating = new CountDownLatch(1), resume = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = pool.submit(() -> f.filter.transition(f.service, "state", "activating", () -> {activating.countDown(); await(resume); f.service.setState("activating"); return true;}));
            assertTrue(activating.await(3, TimeUnit.SECONDS));
            var archive = pool.submit(() -> assertCode("ArchiveRequiresStopped", () -> f.update(f.service, true)));
            resume.countDown(); start.get(3, TimeUnit.SECONDS); archive.get(3, TimeUnit.SECONDS);
            assertFalse(ArchiveValidationFilter.archived(f.service)); assertEquals(0, f.writes.get());
        } finally {resume.countDown(); pool.shutdownNow();}
    }

    @Test public void idempotentTrueReloadsAfterConcurrentRestoreAndActivate() throws Exception {
        Fixture f = new Fixture(); DataAccessor.setField(f.service, "archived", true);
        CountDownLatch snapshot = new CountDownLatch(1), resume = new CountDownLatch(1);
        ResourceManager next = f.next(f.service);
        ResourceManager delayed = (ResourceManager) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ResourceManager.class}, (p,m,a) -> {
            if (m.getName().equals("getById")) {
                ServiceRecord old = new ServiceRecord();
                old.setId(2L); old.setAccountId(1L); old.setStackId(1L); old.setState("inactive");old.setKind("service");old.setName("svc");
                DataAccessor.setField(old,"archived",true);
                snapshot.countDown(); await(resume); return old;
            }
            Object value = m.invoke(next, a);
            return value;
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var archive = pool.submit(() -> assertCode("ArchiveRequiresStopped", () -> f.filter.update("service", "2", request(Map.of("archived", true)), delayed)));
            assertTrue(snapshot.await(3, TimeUnit.SECONDS));
            f.update(f.service, false);
            assertTrue(f.filter.transition(f.service, "state", "activating", () -> {f.service.setState("activating"); return true;}));
            resume.countDown(); archive.get(3, TimeUnit.SECONDS);
            assertFalse(ArchiveValidationFilter.archived(f.service)); assertEquals(1, f.writes.get());
        } finally {resume.countDown(); pool.shutdownNow();}
    }

    @Test public void parentArchiveSerializesChildActivateAndCreateStartOnCreateInBothOrders() throws Exception {
        for(String operation:List.of("activate","create")) {
            parentRace(operation,true);
            parentRace(operation,false);
        }
    }

    private static void parentRace(String operation,boolean archiveFirst) throws Exception {
        Fixture f=new Fixture();
        if(operation.equals("activate"))f.children.add(f.service);
        CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
        Runnable child=()-> {
            if(operation.equals("activate"))f.filter.transition(f.service,"state","activating",()->{
                if(!archiveFirst){entered.countDown();await(resume);}f.service.setState("activating");return true;
            });
            else {
                f.beforeCreate=()->{if(!archiveFirst){entered.countDown();await(resume);}f.service.setState("registering");f.children.add(f.service);};
                ApiRequest request=request(Map.of("stackId",1L,"startOnCreate",true));request.setMethod("POST");
                f.filter.create("service",request,f.next(f.service));
            }
        };
        var pool=Executors.newFixedThreadPool(2);
        try {
            if(archiveFirst) {
                f.beforeWrite=()->{entered.countDown();await(resume);};
                var archive=pool.submit(()->f.update(f.stack,true));assertTrue(operation,entered.await(3,TimeUnit.SECONDS));
                var lifecycle=pool.submit(()->assertCode("ResourceArchived",child));
                resume.countDown();archive.get(3,TimeUnit.SECONDS);lifecycle.get(3,TimeUnit.SECONDS);
                assertTrue(operation,ArchiveValidationFilter.archived(f.stack));assertEquals("inactive",f.service.getState());assertEquals(1,f.writes.get());
            } else {
                var lifecycle=pool.submit(child);assertTrue(operation,entered.await(3,TimeUnit.SECONDS));
                var archive=pool.submit(()->assertCode("ArchiveRequiresStopped",()->f.update(f.stack,true)));
                resume.countDown();lifecycle.get(3,TimeUnit.SECONDS);archive.get(3,TimeUnit.SECONDS);
                assertFalse(operation,ArchiveValidationFilter.archived(f.stack));
                assertEquals(operation.equals("activate")?"activating":"registering",f.service.getState());
            }
        } finally {resume.countDown();pool.shutdownNow();}
    }

    @Test public void childArchiveSerializesBackgroundInstanceStartInBothOrders() throws Exception {
        for(boolean archiveFirst:List.of(true,false)) {
            Fixture f=new Fixture();InstanceRecord instance=instance(9,"stopped");f.related.add(instance);
            CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
            Runnable start=()->f.filter.transition(instance,"state","starting",()->{
                if(!archiveFirst){entered.countDown();await(resume);}instance.setState("starting");return true;
            });
            var pool=Executors.newFixedThreadPool(2);
            try {
                if(archiveFirst) {
                    f.beforeWrite=()->{entered.countDown();await(resume);};
                    var archive=pool.submit(()->f.update(f.service,true));assertTrue(entered.await(3,TimeUnit.SECONDS));
                    var background=pool.submit(()->assertCode("ResourceArchived",start));
                    resume.countDown();archive.get(3,TimeUnit.SECONDS);background.get(3,TimeUnit.SECONDS);
                    assertEquals("stopped",instance.getState());assertTrue(ArchiveValidationFilter.archived(f.service));
                } else {
                    var background=pool.submit(start);assertTrue(entered.await(3,TimeUnit.SECONDS));
                    var archive=pool.submit(()->assertCode("ArchiveRequiresStopped",()->f.update(f.service,true)));
                    resume.countDown();background.get(3,TimeUnit.SECONDS);archive.get(3,TimeUnit.SECONDS);
                    assertEquals("starting",instance.getState());assertFalse(ArchiveValidationFilter.archived(f.service));assertEquals(0,f.writes.get());
                }
            } finally {resume.countDown();pool.shutdownNow();}
        }
    }

    @Test public void selectorChangesRequireRestoreWithoutWritingOrLeavingAProcessTransition() {
        Fixture f=new Fixture();DataAccessor.setField(f.service,"archived",true);
        assertCode("ResourceArchived",()->f.filter.update("service","2",request(Map.of("selectorContainer","app=test")),f.next(f.service)));
        assertEquals(0,f.writes.get());assertEquals("inactive",f.service.getState());assertNull(f.service.getSelectorContainer());
        DataAccessor.setField(f.service,"archived",false);DataAccessor.setField(f.stack,"archived",true);
        assertCode("ResourceArchived",()->f.filter.update("service","2",request(Map.of("selectorContainer","app=test")),f.next(f.service)));
        assertEquals(0,f.writes.get());
    }

    @Test public void newAssociationAndArchiveAreSerializedInBothOrders() throws Exception {
        for(boolean archiveFirst:List.of(true,false)) {
            Fixture f=new Fixture();CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
            InstanceRecord instance=instance(9,"running");instance.setServiceId(null);instance.setStackId(null);
            Runnable attach=()->f.filter.associate(f.service,instance,()->{
                if(!archiveFirst){entered.countDown();await(resume);}f.related.add(instance);return new io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord();
            });
            var pool=Executors.newFixedThreadPool(2);
            try {
                if(archiveFirst) {
                    f.beforeWrite=()->{entered.countDown();await(resume);};
                    var archive=pool.submit(()->f.update(f.service,true));assertTrue(entered.await(3,TimeUnit.SECONDS));
                    var association=pool.submit(()->assertCode("ResourceArchived",attach));
                    resume.countDown();archive.get(3,TimeUnit.SECONDS);association.get(3,TimeUnit.SECONDS);
                    assertTrue(f.related.isEmpty());assertTrue(ArchiveValidationFilter.archived(f.service));
                } else {
                    var association=pool.submit(attach);assertTrue(entered.await(3,TimeUnit.SECONDS));
                    var archive=pool.submit(()->assertCode("ArchiveRequiresStopped",()->f.update(f.service,true)));
                    resume.countDown();association.get(3,TimeUnit.SECONDS);archive.get(3,TimeUnit.SECONDS);
                    assertEquals(1,f.related.size());assertFalse(ArchiveValidationFilter.archived(f.service));
                }
            } finally {resume.countDown();pool.shutdownNow();}
        }
    }

    @Test public void springProcessParserInjectsTheActualArchiveGuardAndDao() {
        Fixture f=new Fixture();DataAccessor.setField(f.service,"archived",true);
        try(GenericApplicationContext context=new GenericApplicationContext()) {
            AnnotationConfigUtils.registerAnnotationConfigProcessors(context);
            context.registerBean("objectManager",ObjectManager.class,()->f.filter.objectManager);
            context.registerBean("lockManager",LockManager.class,()->f.filter.lockManager);
            context.registerBean("extensionManager",io.cattle.platform.extension.ExtensionManager.class,()->
                    (io.cattle.platform.extension.ExtensionManager)Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{io.cattle.platform.extension.ExtensionManager.class},(p,m,a)->List.of()));
            context.registerBean(io.cattle.platform.json.JacksonJsonMapper.class);
            var configuration=DSL.using(SQLDialect.H2).configuration();
            context.registerBean("configuration",org.jooq.Configuration.class,()->configuration);
            context.registerBean(ArchiveValidationFilter.class);
            context.registerBean(ArchiveValidationFilter.ArchiveInstances.class);
            String xml="""
                <beans xmlns="http://www.springframework.org/schema/beans"
                    xmlns:process="http://cattle.io/schemas/spring/process/generic-process">
                    <process:process id="wired" name="service.activate" resourceType="service"
                        start="inactive" transitioning="activating" done="active"/>
                </beans>
                """;
            XmlBeanDefinitionReader reader=new XmlBeanDefinitionReader(context);reader.setValidating(false);
            reader.loadBeanDefinitions(new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)));context.refresh();
            assertSame(configuration,context.getBean(ArchiveValidationFilter.ArchiveInstances.class).getConfiguration());
            GenericResourceProcessDefinition definition=context.getBean("wired",GenericResourceProcessDefinition.class);
            var state=definition.constructProcessState(new LaunchConfiguration("service.activate","service","2",1L,null,Map.of()));
            assertCode("ResourceArchived",state::setTransitioning);
            assertEquals("inactive",f.service.getState());assertEquals(0,f.writes.get());
        }
    }

    @Test public void independentStartCannotSlipPastANewSelectorParentThenArchive() throws Exception {
        Fixture f=new Fixture();f.mappedServices.clear();
        InstanceRecord independent=instance(9,"stopped");independent.setStackId(null);independent.setServiceId(null);
        CountDownLatch starting=new CountDownLatch(1),resume=new CountDownLatch(1),associationWaiting=new CountDownLatch(1);
        AtomicInteger instanceLocks=new AtomicInteger();
        f.beforeLock=id->{if(id.equals("archive.instance.9")&&instanceLocks.incrementAndGet()==2)associationWaiting.countDown();};
        var pool=Executors.newFixedThreadPool(2);
        try {
            var start=pool.submit(()->f.filter.transition(independent,"state","starting",()->{starting.countDown();await(resume);independent.setState("starting");return true;}));
            assertTrue(starting.await(3,TimeUnit.SECONDS));
            var attachAndArchive=pool.submit(()->{
                f.filter.associate(f.service,independent,()->{f.mappedServices.add(f.service);f.related.add(independent);return new io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord();});
                assertCode("ArchiveRequiresStopped",()->f.update(f.service,true));
            });
            assertTrue("association must serialize with the previously independent start",associationWaiting.await(3,TimeUnit.SECONDS));
            resume.countDown();start.get(3,TimeUnit.SECONDS);attachAndArchive.get(3,TimeUnit.SECONDS);
            assertEquals("starting",independent.getState());assertFalse(ArchiveValidationFilter.archived(f.service));assertEquals(0,f.writes.get());
        } finally {resume.countDown();pool.shutdownNow();}
    }

    @Test public void activateServicesPreflightRejectsArchivedChildrenBeforeAnyBatchEffectsButStopIsUntouched() {
        Fixture f=new Fixture();f.children.add(f.service);DataAccessor.setField(f.service,"archived",true);
        AtomicInteger actions=new AtomicInteger();
        ResourceManager next=(ResourceManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResourceManager.class},(p,m,a)->{
            if(m.getName().equals("getById"))return f.stack;if(m.getName().equals("resourceAction")){actions.incrementAndGet();return f.stack;}throw new UnsupportedOperationException(m.getName());
        });
        ApiRequest action=request(Map.of());action.setMethod("POST");action.setAction("activateservices");action.setType("stack");action.setId("1");
        assertCode("ResourceArchived",()->f.filter.resourceAction("stack",action,next));assertEquals(0,actions.get());
        action.setAction("deactivateservices");f.filter.resourceAction("stack",action,next);assertEquals(1,actions.get());
    }

    @Test public void typedArchiveAndRestoreReuseTheOwnerWithoutStartingStoppingOrCascadingFlags() {
        Fixture f = new Fixture(); f.children.add(f.service);
        f.filter.withChange(f.service, true, () -> f.update(f.service, true));
        f.filter.withChange(f.stack, true, () -> f.update(f.stack, true));
        f.filter.withChange(f.stack, false, () -> f.update(f.stack, false));
        assertTrue(ArchiveValidationFilter.archived(f.service));
        f.filter.withChange(f.stack, true, () -> f.update(f.stack, true));
        f.filter.withChange(f.service, false, () -> f.update(f.service, false));
        assertTrue(ArchiveValidationFilter.archived(f.stack)); assertFalse(ArchiveValidationFilter.archived(f.service));
        assertEquals("inactive", f.service.getState()); assertEquals("active", f.stack.getState());
        assertCode("ResourceArchived", () -> f.filter.transition(f.service, "state", "activating", () -> true));
    }

    @Test public void typedPreviewRejectsNonStoppedSidekicksAndInfrastructureBeforeItsCallback() {
        Fixture active = new Fixture(); active.service.setState("active");
        assertCode("ArchiveRequiresStopped", () -> active.filter.withChange(active.service, true, () -> active.update(active.service, true)));
        Fixture sidekick = new Fixture(); sidekick.related.add(instance(9, "running"));
        assertCode("ArchiveRequiresStopped", () -> sidekick.filter.withChange(sidekick.stack, true, () -> sidekick.update(sidekick.stack, true)));
        Fixture infra = new Fixture(); DataAccessor.setField(infra.stack, "group", "infra");
        assertCode("ArchiveSystemResource", () -> infra.filter.withChange(infra.service, true, () -> infra.update(infra.service, true)));
        assertEquals(0, active.writes.get() + sidekick.writes.get() + infra.writes.get());
        DataAccessor.setField(infra.service, "archived", true);
        infra.filter.withChange(infra.service, false, () -> infra.update(infra.service, false));
        assertFalse(ArchiveValidationFilter.archived(infra.service));
    }

    @Test public void typedRowLockedCallerNeverWaitsBehindTheOrdinaryArchiveLeaseAndPutReentersIt() throws Exception {
        Fixture f = new Fixture(); CountDownLatch ordinaryOwnsLease = new CountDownLatch(1), release = new CountDownLatch(1);
        f.beforeWrite = () -> { ordinaryOwnsLease.countDown(); await(release); };
        var pool = Executors.newSingleThreadExecutor();
        try {
            var ordinary = pool.submit(() -> f.update(f.service, true));
            assertTrue(ordinaryOwnsLease.await(3, TimeUnit.SECONDS));
            assertCode("ArchiveConcurrentChange", () -> f.filter.withChange(f.service, true, () -> f.update(f.service, true)));
            assertEquals(0, f.writes.get());
            release.countDown(); ordinary.get(3, TimeUnit.SECONDS);
            f.beforeWrite = () -> { }; AtomicInteger acquisitions = new AtomicInteger();
            f.beforeLock = id -> acquisitions.incrementAndGet();
            f.filter.withChange(f.service, false, () -> f.update(f.service, false));
            assertEquals("filtered PUT must reuse the exact lease", 1, acquisitions.get());
            assertFalse(ArchiveValidationFilter.archived(f.service));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test public void typedPutKeepsTheFreshLockedTargetInsteadOfAnOlderOrdinaryReload() {
        Fixture f = new Fixture();
        ServiceRecord stale = new ServiceRecord(); stale.setId(2L); stale.setStackId(99L); stale.setState("active");
        stale.setAccountId(1L); stale.setKind("service"); f.reloadedService = stale; f.readTarget = stale;
        List<String> locks = new ArrayList<>(); f.beforeLock = locks::add;
        DataAccessor.setField(f.service, "archived", true);
        f.filter.withChange(f.service, false, () -> f.update(f.service, false));
        assertFalse("a stale false archive value must not turn restore into a no-op", ArchiveValidationFilter.archived(f.service));
        assertEquals(List.of("archive.stack.1"), locks); locks.clear();
        f.filter.withChange(f.service, true, () -> f.update(f.service, true));
        assertTrue("the stopped locked target must be used for the filtered PUT", ArchiveValidationFilter.archived(f.service));
        assertEquals(List.of("archive.stack.1"), locks);
        assertCode("ArchiveRequiresStopped", () -> f.update(f.service, true));
        assertEquals(2, f.writes.get());
    }

    private static void await(CountDownLatch latch) {
        try {assertTrue("barrier timed out", latch.await(5, TimeUnit.SECONDS));} catch (InterruptedException e) {Thread.currentThread().interrupt(); throw new AssertionError(e);}
    }
    private static void assertCode(String code, Runnable action) {try {action.run(); fail("Expected " + code);} catch(ClientVisibleException e){assertEquals(code,e.getCode());}}
    private static ApiRequest request(Map<String,Object> body) {ApiRequest r=new ApiRequest(null,null);r.setMethod("PUT");r.setRequestObject(new LinkedHashMap<>(body));return r;}
    private static StackRecord stack(long id) {StackRecord s=new StackRecord();s.setId(id);s.setAccountId(1L);s.setState("active");s.setSystem(false);return s;}
    private static InstanceRecord instance(long id,String state) {InstanceRecord i=new InstanceRecord();i.setId(id);i.setAccountId(1L);i.setStackId(1L);i.setServiceId(2L);i.setState(state);return i;}

    private static class Fixture {
        final ArchiveValidationFilter filter = new ArchiveValidationFilter();
        final StackRecord stack=stack(1); final ServiceRecord service=new ServiceRecord();
        final Map<Long,Stack> stacks=new ConcurrentHashMap<>();
        final List<Service> children=new ArrayList<>(); final List<Instance> related=new ArrayList<>();
        final List<Service> mappedServices=new ArrayList<>();
        Service reloadedService;
        Object readTarget;
        final AtomicInteger writes=new AtomicInteger(); Runnable beforeWrite=()->{},beforeCreate=()->{};
        java.util.function.Consumer<String> beforeLock=id->{};
        Fixture() {
            service.setId(2L);service.setAccountId(1L);service.setStackId(1L);service.setState("inactive");service.setKind("service");service.setName("svc");stacks.put(1L,stack);
            mappedServices.add(service);
            filter.objectManager=(ObjectManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ObjectManager.class},(p,m,a)-> {
                if(m.getName().equals("reload"))return a[0] instanceof Service ? reloadedService == null ? service : reloadedService : a[0];
                if(m.getName().equals("loadResource")&&a[0]==Stack.class)return stacks.get(a[1]);
                if(m.getName().equals("loadResource")&&a[0].equals("service"))return service;
                if(m.getName().equals("find"))return children;
                throw new UnsupportedOperationException(m.getName());
            });
            Map<String,ReentrantLock> locks=new ConcurrentHashMap<>();
            filter.lockManager=(LockManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{LockManager.class},(p,m,a)->{
                beforeLock.accept(((LockDefinition)a[0]).getLockId());
                ReentrantLock lock=locks.computeIfAbsent(((LockDefinition)a[0]).getLockId(),key->new ReentrantLock());
                if (m.getName().equals("tryLock")) { if (!lock.tryLock()) return null; }
                else lock.lock();
                try{return ((LockCallback<?>)a[1]).doWithLock();}finally{lock.unlock();}
            });
            filter.instances=new ArchiveValidationFilter.ArchiveInstances(){
                @Override public List<? extends Instance> related(Object r){return related;}
                @Override public List<? extends Service> lifecycleOwners(Instance i){return mappedServices;}
            };
            ArchiveEligibilityRead reader = new ArchiveEligibilityRead(); reader.owner = () -> filter;
            filter.eligibilityRead = () -> reader;
        }
        Object update(Object target,boolean value){return filter.update(target instanceof Stack?"stack":"service",target instanceof Stack?"1":"2",request(Map.of("archived",value)),next(target));}
        ResourceManager next(Object target){return (ResourceManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ResourceManager.class},(p,m,a)->{
            if(m.getName().equals("getById"))return readTarget == null ? target : readTarget;
            if(m.getName().equals("update")){
                beforeWrite.run();
                Map<?,?> body=(Map<?,?>)((ApiRequest)a[2]).getRequestObject();
                if(body.containsKey("archived"))DataAccessor.setField(target,"archived",body.get("archived"));
                if(body.containsKey("name"))service.setName(body.get("name").toString());
                writes.incrementAndGet();return target;
            }
            if(m.getName().equals("create")){beforeCreate.run();writes.incrementAndGet();return target;}
            throw new UnsupportedOperationException(m.getName());
        });}
    }
}
