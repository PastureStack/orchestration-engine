package io.cattle.platform.db.jooq.utils;

import jakarta.xml.bind.annotation.XmlTransient;

import org.jooq.Table;
import org.jooq.TableRecord;

public interface TableRecordJaxb<R extends TableRecord<R>> {

    @XmlTransient
    Table<R> getTable();

}
