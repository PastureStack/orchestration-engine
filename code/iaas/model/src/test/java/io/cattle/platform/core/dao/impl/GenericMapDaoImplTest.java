package io.cattle.platform.core.dao.impl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import io.cattle.platform.core.dao.GenericMapDao;
import io.cattle.platform.core.model.InstanceHostMap;
import io.cattle.platform.core.model.tables.records.InstanceHostMapRecord;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class GenericMapDaoImplTest {

    @Test
    public void castRecordListReturnsCheckedCopyWithOriginalRecords() {
        TestGenericMapDao dao = new TestGenericMapDao();
        InstanceHostMapRecord record = new InstanceHostMapRecord();
        List<InstanceHostMapRecord> records = Arrays.asList(record);

        List<? extends InstanceHostMap> result = dao.recordList(InstanceHostMap.class, records);

        assertNotSame(records, result);
        assertEquals(1, result.size());
        assertSame(record, result.get(0));
    }

    @Test
    public void castRecordListRejectsWrongRecordTypeBeforeReturning() {
        TestGenericMapDao dao = new TestGenericMapDao();

        try {
            dao.recordList(InstanceHostMap.class, Arrays.asList(new Object()));
        } catch (ClassCastException e) {
            assertTrue(e.getMessage().contains(InstanceHostMap.class.getName()));
            return;
        }

        throw new AssertionError("Expected wrong record type to fail at checked boundary");
    }

    @Test
    public void castRecordAllowsNullAndChecksNonNullRecords() {
        TestGenericMapDao dao = new TestGenericMapDao();
        InstanceHostMapRecord record = new InstanceHostMapRecord();

        assertNull(dao.record(InstanceHostMap.class, null));
        assertSame(record, dao.record(InstanceHostMap.class, record));
    }

    @Test
    public void genericMapListBoundariesKeepWildcardExtendsReturnShape() throws Exception {
        assertWildcardExtendsList(GenericMapDao.class.getMethod("findNonRemoved", Class.class, Class.class, long.class));
        assertWildcardExtendsList(GenericMapDao.class.getMethod("findToRemove", Class.class, Class.class, long.class));
        assertWildcardExtendsList(GenericMapDao.class.getMethod("findNonPurged", Class.class, Class.class, long.class));

        Method castRecordList = GenericMapDaoImpl.class.getDeclaredMethod("castRecordList", Class.class, List.class);
        assertTrue(Modifier.isProtected(castRecordList.getModifiers()));
        assertWildcardExtendsList(castRecordList);
    }

    @Test
    public void castRecordListDoesNotExposeSourceListMutations() {
        TestGenericMapDao dao = new TestGenericMapDao();
        InstanceHostMapRecord record = new InstanceHostMapRecord();
        List<InstanceHostMapRecord> records = new ArrayList<InstanceHostMapRecord>(Arrays.asList(record));

        List<? extends InstanceHostMap> result = dao.recordList(InstanceHostMap.class, records);

        records.clear();
        assertEquals(1, result.size());
        assertSame(record, result.get(0));
    }

    private static void assertWildcardExtendsList(Method method) {
        assertEquals(1, method.getTypeParameters().length);
        assertEquals("T", method.getTypeParameters()[0].getName());
        assertEquals("java.util.List<? extends T>", method.getGenericReturnType().getTypeName());
    }

    private static class TestGenericMapDao extends GenericMapDaoImpl {
        <T> List<? extends T> recordList(Class<T> mapType, List<?> records) {
            return castRecordList(mapType, records);
        }

        <T> T record(Class<T> mapType, Object record) {
            return castRecord(mapType, record);
        }
    }
}
