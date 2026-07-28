package io.cattle.platform.db.jooq.mapper;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.jooq.Field;
import org.jooq.Name;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.impl.TableImpl;
import org.junit.Test;

public class MultiRecordMapperTest {

    @Test
    public void addReturnsAliasAndTracksSelectedFields() {
        TestMapper mapper = new TestMapper();
        SampleTable table = new SampleTable("sample");

        Table<?> alias = mapper.add(table);

        assertEquals("sample_0", alias.getName());
        assertEquals(3, mapper.fields().size());
    }

    @Test
    public void addKeepsIdAndSelectedFieldsForAliasProjection() {
        TestMapper mapper = new TestMapper();
        SampleTable table = new SampleTable("sample");
        Field<?> name = table.NAME;

        SampleTable alias = mapper.add(table, SampleTable.class, name);

        assertEquals("sample_0", alias.getName());
        assertEquals(2, mapper.fields().size());
        assertEquals("sample_0_id", mapper.fields().get(0).getName());
        assertEquals("sample_0_name", mapper.fields().get(1).getName());
    }

    private static class TestMapper extends MultiRecordMapper<List<Object>> {
        @Override
        protected List<Object> map(List<Object> input) {
            return input;
        }
    }

    private static class SampleTable extends TableImpl<Record> {
        private static final long serialVersionUID = 1L;

        final Field<Integer> ID = createField(DSL.name("id"), SQLDataType.INTEGER, this, "");
        final Field<String> NAME = createField(DSL.name("name"), SQLDataType.VARCHAR, this, "");
        final Field<String> STATE = createField(DSL.name("state"), SQLDataType.VARCHAR, this, "");

        SampleTable(String name) {
            this(DSL.name(name));
        }

        SampleTable(Name name) {
            super(name);
        }

        @Override
        public Class<? extends Record> getRecordType() {
            return Record.class;
        }

        @Override
        public SampleTable as(String alias) {
            return new SampleTable(alias);
        }

        @Override
        public SampleTable as(Name alias) {
            return new SampleTable(alias);
        }
    }
}
