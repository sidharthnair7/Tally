package dev.tally.audit;

import dev.tally.data.Backend;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Every row behind every finding, keyed "system.table:primary_key", so every sentence can cite its rows. */
public class Evidence {

    static final Map<String, String> KEYS = Map.of(
            "erp.invoices", "invoice_id", "erp.payments", "payment_id", "erp.policies", "policy",
            "vendors.vendors", "vendor_id", "hr.employees", "employee_id", "bank.transactions", "txn_id");

    private final Backend db;
    public final Map<String, Map<String, Object>> rows = new LinkedHashMap<>();

    public Evidence(Backend db) {
        this.db = db;
    }

    /** Fetches the rows it doesn't have yet, in one query, and returns the refs for all the ids asked for. */
    public List<String> fetch(String source, Collection<?> ids, String purpose) {
        TreeSet<String> wanted = new TreeSet<>();
        for (Object i : ids) {
            if (i != null && !String.valueOf(i).isEmpty()) wanted.add(String.valueOf(i));
        }
        List<String> missing = wanted.stream().filter(i -> !rows.containsKey(source + ":" + i)).toList();
        if (!missing.isEmpty()) {
            String key = KEYS.get(source);
            add(source, db.query("SELECT * FROM @" + source + " WHERE " + key + " IN " + Sql.list(missing), purpose));
        }
        List<String> refs = new ArrayList<>();
        for (String i : wanted) {
            if (rows.containsKey(source + ":" + i)) refs.add(source + ":" + i);
        }
        return refs;
    }

    public void add(String source, List<Map<String, Object>> found) {
        String key = KEYS.get(source);
        for (Map<String, Object> r : found) rows.put(source + ":" + r.get(key), r);
    }

    public Map<String, Object> get(String ref) {
        return rows.get(ref);
    }

    public Map<String, Object> employee(String id) {
        return rows.get("hr.employees:" + id);
    }

    static String joinIds(Collection<String> ids) {
        return ids.stream().collect(Collectors.joining(", "));
    }
}
