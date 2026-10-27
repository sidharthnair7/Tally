package dev.tally.data;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where Tally reads the books. Two backends run the same SQL:
 * {@link ZetarisBackend} reads the four systems as Zetaris datasources, in place (live mode);
 * {@link H2Backend} reads the same four exports from data/sources (offline mode: tests and the hosted demo).
 *
 * SQL is written once with @system.table placeholders ("@erp.invoices"); each backend says where that table lives.
 * Every query is logged with its purpose, the systems it touched, its row count and its time, for the trace.
 */
public abstract class Backend {

    public static final Map<String, Map<String, String>> SYSTEMS = Map.of(
            "erp", Map.of("name", "ERP", "about", "Accounting: invoices, payments, approval policy"),
            "vendors", Map.of("name", "Vendor master", "about", "Procurement's approved vendors"),
            "hr", Map.of("name", "HR", "about", "Employee records"),
            "bank", Map.of("name", "Bank feed", "about", "The bank's statement transactions"));
    public static final List<String> SYSTEM_ORDER = List.of("erp", "vendors", "hr", "bank");
    public static final Map<String, List<String>> TABLES = Map.of(
            "erp", List.of("invoices", "payments", "policies"), "vendors", List.of("vendors"),
            "hr", List.of("employees"), "bank", List.of("transactions"));
    private static final Pattern PLACEHOLDER = Pattern.compile("@(erp|vendors|hr|bank)\\.(\\w+)");

    public final List<Map<String, Object>> log = new ArrayList<>();

    public abstract String mode();

    public abstract String label();

    protected abstract String where(String system, String table);

    protected abstract List<Map<String, Object>> run(String sql);

    public String resolve(String sql) {
        Matcher m = PLACEHOLDER.matcher(sql);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(where(m.group(1), m.group(2))));
        }
        return m.appendTail(out).toString().strip();
    }

    public synchronized List<Map<String, Object>> query(String sql, String purpose) {
        String resolved = resolve(sql);
        long start = System.nanoTime();
        List<Map<String, Object>> rows = run(resolved);
        double ms = Math.round((System.nanoTime() - start) / 100_000.0) / 10.0;
        TreeSet<String> systems = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(sql);
        while (m.find()) {
            systems.add(m.group(1));
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("purpose", purpose);
        entry.put("sql", resolved);
        entry.put("systems", new ArrayList<>(systems));
        entry.put("rows", rows.size());
        entry.put("ms", ms);
        log.add(entry);
        return rows;
    }

    /** Dates become ISO strings and decimals become doubles, so rows read the same from either backend. */
    static Object plain(Object v) {
        if (v instanceof Date d) return d.toLocalDate().toString();
        if (v instanceof LocalDate d) return d.toString();
        if (v instanceof Timestamp t) return t.toLocalDateTime().toString();
        if (v instanceof LocalDateTime t) return t.toLocalTime().equals(java.time.LocalTime.MIDNIGHT) ? t.toLocalDate().toString() : t.toString();
        if (v instanceof BigDecimal b) return b.doubleValue();
        return v;
    }
}
