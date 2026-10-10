package io.github.ibuildthecloud.gdapi.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.TimeZone;

public class DateUtils {

    public static final String DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    public static Date parse(String date) throws ParseException {
        if (date == null)
            return null;

        if ("now".equals(date)) {
            return new Date();
        }

        try {
            // API dates carry their own offset. Never interpret a literal Z
            // in the host's default timezone or lose fractional seconds.
            return Date.from(Instant.parse(date));
        } catch (DateTimeParseException e) {
            throw new ParseException("Invalid ISO-8601 timestamp", e.getErrorIndex());
        } catch (IllegalArgumentException e) {
            // Instants outside java.util.Date's range are invalid API dates too.
            throw new ParseException("Invalid ISO-8601 timestamp", 0);
        }
    }

    public static String toString(Date date) {
        if (date == null)
            return null;

        SimpleDateFormat df = new SimpleDateFormat(DATE_FORMAT);
        df.setTimeZone(TimeZone.getTimeZone("GMT"));
        return df.format(date);
    }
}
