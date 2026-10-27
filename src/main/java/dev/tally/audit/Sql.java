package dev.tally.audit;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Small helpers for reading rows and writing safe SQL literals. */
public final class Sql {

    private Sql() {
    }

    public static String lit(Object v) {
        return "'" + String.valueOf(v).replace("'", "''") + "'";
    }

    public static String list(Collection<?> values) {
        TreeSet<String> sorted = new TreeSet<>();
        for (Object v : values) sorted.add(String.valueOf(v));
        return "(" + sorted.stream().map(Sql::lit).collect(Collectors.joining(", ")) + ")";
    }

    public static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static double num(Object v) {
        return Math.round(((Number) (v instanceof String s ? Double.parseDouble(s) : v)).doubleValue() * 100) / 100.0;
    }

    public static LocalDate date(Object v) {
        return LocalDate.parse(String.valueOf(v).substring(0, 10));
    }

    public static String day(Object v) {
        return String.valueOf(v).substring(0, 10);
    }

    public static long daysBetween(Object from, Object to) {
        return ChronoUnit.DAYS.between(date(from), date(to));
    }

    public static String usd(double x) {
        return String.format("$%,.2f", x);
    }
}
