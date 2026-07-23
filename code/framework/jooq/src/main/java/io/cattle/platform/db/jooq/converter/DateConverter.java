package io.cattle.platform.db.jooq.converter;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

import org.jooq.Converter;

public class DateConverter implements Converter<LocalDateTime, Date> {

    private static final long serialVersionUID = -3093938632174221235L;

    @Override
    public Date from(LocalDateTime databaseObject) {
        if (databaseObject == null) {
            return null;
        }
        return Date.from(databaseObject.atZone(ZoneId.systemDefault()).toInstant());
    }

    @Override
    public LocalDateTime to(Date userObject) {
        if (userObject == null) {
            return null;
        }
        return LocalDateTime.ofInstant(userObject.toInstant(), ZoneId.systemDefault());
    }

    @Override
    public Class<LocalDateTime> fromType() {
        return LocalDateTime.class;
    }

    @Override
    public Class<Date> toType() {
        return Date.class;
    }

}
