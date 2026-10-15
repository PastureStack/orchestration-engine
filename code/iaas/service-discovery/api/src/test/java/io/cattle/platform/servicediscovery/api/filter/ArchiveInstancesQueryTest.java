package io.cattle.platform.servicediscovery.api.filter;

import static org.junit.Assert.*;
import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.core.model.tables.records.StackRecord;
import io.cattle.platform.core.model.Stack;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.util.DataAccessor;
import io.github.ibuildthecloud.gdapi.exception.ClientVisibleException;
import java.lang.reflect.Proxy;
import java.sql.DriverManager;
import java.util.List;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.DataType;
import org.jooq.SQLDialect;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.Test;

/** Executes the production ownership query against a disposable in-memory database. */
public class ArchiveInstancesQueryTest {
    @Test public void includesDirectOwnershipAndRemovingSidekicksButNotRemovedSelectorMapsOrOtherAccounts() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:archive-"+UUID.randomUUID()+";DATABASE_TO_UPPER=false")) {
            DSLContext db=DSL.using(connection,SQLDialect.H2);
            for(Table<?> table:List.of(INSTANCE,SERVICE,SERVICE_EXPOSE_MAP)) {
                if(table.getSchema()!=null)db.createSchemaIfNotExists(table.getSchema()).execute();
                var ddl=db.createTable(table);
                for(Field<?> field:table.fields()) ddl=ddl.column(field.getName(),testType(field));
                ddl.execute();
            }
            db.insertInto(SERVICE,SERVICE.ID,SERVICE.ACCOUNT_ID,SERVICE.STACK_ID,SERVICE.STATE)
                    .values(2L,1L,1L,"inactive").execute();
            db.insertInto(INSTANCE,INSTANCE.ID,INSTANCE.ACCOUNT_ID,INSTANCE.SERVICE_ID,INSTANCE.STACK_ID,INSTANCE.STATE)
                    .values(10L,1L,2L,null,"running")
                    .values(11L,1L,null,1L,"running")
                    .values(12L,1L,null,null,"running")
                    .values(13L,1L,null,null,"stopped")
                    .values(14L,99L,2L,1L,"running").execute();
            db.insertInto(SERVICE_EXPOSE_MAP,SERVICE_EXPOSE_MAP.ID,SERVICE_EXPOSE_MAP.ACCOUNT_ID,SERVICE_EXPOSE_MAP.SERVICE_ID,
                    SERVICE_EXPOSE_MAP.INSTANCE_ID,SERVICE_EXPOSE_MAP.STATE,SERVICE_EXPOSE_MAP.REMOVED,SERVICE_EXPOSE_MAP.DNS_PREFIX,SERVICE_EXPOSE_MAP.UPGRADE)
                    .values(20L,1L,2L,12L,"removing",null,"old-sidekick",true)
                    .values(21L,1L,2L,13L,"removed",new Date(),"detached",false).execute();
            ArchiveValidationFilter.ArchiveInstances dao=new ArchiveValidationFilter.ArchiveInstances();dao.setConfiguration(db.configuration());
            ServiceRecord service=new ServiceRecord();service.setId(2L);service.setAccountId(1L);service.setStackId(1L);
            StackRecord stack=new StackRecord();stack.setId(1L);stack.setAccountId(1L);
            assertEquals(List.of(10L,12L),dao.related(service).stream().map(i->i.getId()).sorted().collect(Collectors.toList()));
            assertEquals(List.of(10L,11L,12L),dao.related(stack).stream().map(i->i.getId()).sorted().collect(Collectors.toList()));
            InstanceRecord removing=new InstanceRecord();removing.setId(12L);removing.setAccountId(1L);
            assertEquals(1,dao.lifecycleOwners(removing).size());
            InstanceRecord detached=new InstanceRecord();detached.setId(13L);detached.setAccountId(1L);
            assertTrue("a detached independent container must remain startable",dao.lifecycleOwners(detached).isEmpty());
        }
    }

    @Test public void existingSelectorReferencesDoNotOwnStartsButManagedUpgradeAndDirectParentsDo() throws Exception {
        try(var connection=DriverManager.getConnection("jdbc:h2:mem:archive-owners-"+UUID.randomUUID()+";DATABASE_TO_UPPER=false")) {
            DSLContext db=DSL.using(connection,SQLDialect.H2);
            for(Table<?> table:List.of(INSTANCE,SERVICE,SERVICE_EXPOSE_MAP)) {
                if(table.getSchema()!=null)db.createSchemaIfNotExists(table.getSchema()).execute();
                var ddl=db.createTable(table);
                for(Field<?> field:table.fields()) ddl=ddl.column(field.getName(),testType(field));
                ddl.execute();
            }
            db.insertInto(SERVICE,SERVICE.ID,SERVICE.ACCOUNT_ID,SERVICE.STACK_ID,SERVICE.STATE,SERVICE.DATA)
                    .values(2L,1L,1L,"inactive",Map.of("fields",Map.of("archived",true))).execute();
            db.insertInto(INSTANCE,INSTANCE.ID,INSTANCE.ACCOUNT_ID,INSTANCE.SERVICE_ID,INSTANCE.STACK_ID,INSTANCE.STATE)
                    .values(10L,1L,2L,null,"stopped")
                    .values(11L,1L,null,1L,"stopped")
                    .values(12L,1L,null,null,"stopped")
                    .values(13L,1L,null,null,"stopped")
                    .values(14L,1L,null,null,"stopped")
                    .values(15L,1L,null,null,"stopped").execute();
            db.insertInto(SERVICE_EXPOSE_MAP,SERVICE_EXPOSE_MAP.ID,SERVICE_EXPOSE_MAP.ACCOUNT_ID,SERVICE_EXPOSE_MAP.SERVICE_ID,
                    SERVICE_EXPOSE_MAP.INSTANCE_ID,SERVICE_EXPOSE_MAP.STATE,SERVICE_EXPOSE_MAP.REMOVED,
                    SERVICE_EXPOSE_MAP.MANAGED,SERVICE_EXPOSE_MAP.UPGRADE)
                    .values(20L,1L,2L,12L,"removing",null,true,false)
                    .values(21L,1L,2L,13L,"removed",new Date(),true,true)
                    .values(22L,1L,2L,14L,"active",null,false,false)
                    .values(23L,1L,2L,15L,"active",null,false,true).execute();
            ArchiveValidationFilter.ArchiveInstances dao=new ArchiveValidationFilter.ArchiveInstances();dao.setConfiguration(db.configuration());
            ArchiveValidationFilter filter=new ArchiveValidationFilter();filter.instances=dao;
            StackRecord stack=new StackRecord();stack.setId(1L);stack.setAccountId(1L);stack.setState("active");
            filter.objectManager=(ObjectManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{ObjectManager.class},(p,m,a)->{
                if(m.getName().equals("reload"))return a[0];
                if(m.getName().equals("loadResource")&&a[0]==Stack.class)return stack;
                throw new UnsupportedOperationException(m.getName());
            });
            filter.lockManager=(LockManager)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{LockManager.class},
                    (p,m,a)->((LockCallback<?>)a[1]).doWithLock());
            for(boolean parentArchived:List.of(false,true)) {
                DataAccessor.setField(stack,"archived",parentArchived);
                db.update(INSTANCE).set(INSTANCE.STATE,"stopped").execute();
                for(long id:List.of(10L,11L,12L,13L,14L,15L)) {
                    InstanceRecord instance=db.selectFrom(INSTANCE).where(INSTANCE.ID.eq(id)).fetchOne();
                    assertEquals("only direct, managed or upgrade associations own the instance",id==10||id==12||id==15,
                            !dao.lifecycleOwners(instance).isEmpty());
                    boolean blocked=id==10||id==12||id==15||id==11&&parentArchived;
                    try {
                        assertTrue(filter.transition(instance,"state","starting",()->{
                            db.update(INSTANCE).set(INSTANCE.STATE,"starting").where(INSTANCE.ID.eq(id)).execute();return true;
                        }));
                        assertFalse("archived lifecycle owners must reject the start of "+id,blocked);
                    } catch(ClientVisibleException e) {
                        assertTrue("selector-only and removed references must remain startable: "+id,blocked);
                        assertEquals("ResourceArchived",e.getCode());
                        assertEquals("stopped",db.select(INSTANCE.STATE).from(INSTANCE).where(INSTANCE.ID.eq(id)).fetchOne(INSTANCE.STATE));
                    }
                }
            }
            // A running selector reference still prevents archive: eligibility is broader than ownership.
            ServiceRecord service=db.selectFrom(SERVICE).where(SERVICE.ID.eq(2L)).fetchOne();
            assertEquals(List.of(10L,12L,14L,15L),dao.related(service).stream().map(i->i.getId()).sorted().collect(Collectors.toList()));
            try {filter.assertArchivable(service);fail("running selector references must still block archive");}
            catch(ClientVisibleException e){assertEquals("ArchiveRequiresStopped",e.getCode());}
        }
    }

    private static <T> DataType<T> testType(Field<T> field) {
        return field.getDataType().nullable(true).identity(false).defaultValue((Field<T>) null);
    }
}
