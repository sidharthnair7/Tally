package dev.tally.audit;

import static dev.tally.audit.Sql.lit;
import static dev.tally.audit.Sql.str;
import static dev.tally.audit.Sql.usd;

import dev.tally.data.Backend;
import dev.tally.memory.Memory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * One audit, as a stream of steps for the page's trace:
 * read the four systems in place, load the approval policy, run the six checks, recall the controller's past
 * decisions, follow the people behind the findings, connect findings that share a person, explain each one with
 * the rows behind it.
 *
 * Verdicts come from the checks, the controller's remembered decisions and the connections. A model only words the
 * explanation, and only when every sentence survives the evidence check.
 */
public final class Investigation {

    private static final Map<String, Integer> ORDER = Map.of("ESCALATE", 0, "FLAG", 1, "REMEMBERED", 2, "CLEAR", 3);
    private static final Set<String> SENSITIVE = Set.of("bank_account", "counterparty_account");

    private Investigation() {
    }

    /** Account numbers leave the server as their last four digits only. */
    static Map<String, Object> mask(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((k, v) -> {
            String s = v == null ? "" : String.valueOf(v);
            out.put(k, SENSITIVE.contains(k) && !s.isEmpty() ? "••••" + s.substring(Math.max(0, s.length() - 4)) : v);
        });
        return out;
    }

    public static Map<String, Object> run(Backend db, Memory memory, Explain.Model model, Consumer<Map<String, Object>> emit) {
        Evidence ev = new Evidence(db);
        long started = System.nanoTime();
        List<Finding> findings = new ArrayList<>();
        Step step = new Step(db, emit);

        // 1. Read every system where it lives: one statement counts all of them.
        step.begin();
        String union = Backend.SYSTEM_ORDER.stream().flatMap(s -> Backend.TABLES.get(s).stream()
                        .map(t -> "SELECT '" + s + "." + t + "' AS source, COUNT(*) AS n FROM @" + s + "." + t))
                .collect(Collectors.joining("\nUNION ALL "));
        Map<String, Object> counts = new LinkedHashMap<>();
        for (var r : db.query(union, "Count every record in all four systems at once")) {
            counts.put(str(r, "source"), ((Number) r.get("n")).longValue());
        }
        Map<String, Object> period = db.query("SELECT MIN(invoice_date) AS first_day, MAX(invoice_date) AS last_day FROM @erp.invoices",
                "The period these books cover (ERP)").getFirst();
        String firstDay = Sql.day(period.get("first_day")), lastDay = Sql.day(period.get("last_day"));
        long records = counts.entrySet().stream().filter(e -> !e.getKey().equals("erp.policies"))
                .mapToLong(e -> (long) e.getValue()).sum();
        step.end("read", "Read four systems in place",
                String.format("%,d records across 4 systems, %s to %s", records, firstDay, lastDay), Map.of("counts", counts));

        // 2. The rules come from the company's own policy table, not from Tally's code.
        step.begin();
        List<Map<String, Object>> rows = db.query("SELECT policy, value, description FROM @erp.policies", "The company's approval policy (ERP)");
        ev.add("erp.policies", rows);
        Map<String, Object> policy = new LinkedHashMap<>();
        rows.forEach(r -> policy.put(str(r, "policy"), r.get("value")));
        step.end("policy", "Load the approval policy", "Controller approval from " + usd(Checks.policy(policy, "ap_approval_limit"))
                + ", CFO from " + usd(Checks.policy(policy, "cfo_approval_limit")) + "; a vendor is new for "
                + (int) Checks.policy(policy, "new_vendor_days") + " days", Map.of());

        // 3. The six checks.
        for (Checks.Named c : Checks.ALL) {
            step.begin();
            Checks.Result result = c.check().run(db, ev, policy);
            findings.addAll(result.found());
            step.end(c.code(), c.name(), result.note(), Map.of("found", result.found().stream().filter(f -> !f.verdict.equals("CLEAR")).count()));
        }

        // 4. What has the controller already decided?
        step.begin();
        int remembered = 0;
        for (Finding f : findings) {
            if (f.verdict.equals("CLEAR")) continue;
            Map<String, Object> hit = memory.recall(f.fingerprint(), f.title);
            if (hit == null) continue;
            f.memory = hit;
            if ("expected".equals(hit.get("decision"))) {
                f.verdict = "REMEMBERED";
                remembered++;
                f.reasons.add("The controller marked this pattern as expected; Tally remembered and set it aside.");
                memory.used(String.valueOf(hit.get("id")));
            } else {
                f.reasons.add("The controller already confirmed this pattern as fraud.");
            }
        }
        step.end("memory", "Recall the controller's past decisions",
                remembered + " finding(s) match a decision the controller already made (" + memory.kind() + ")", Map.of());

        // 5. Follow the people: who set up the flagged vendors, and what else did they touch?
        step.begin();
        Set<Object> peopleIds = new TreeSet<>();
        for (Finding f : findings) {
            peopleIds.addAll(f.actors);
            for (String k : List.of("created_by", "requested_by", "approved_by", "employee_id", "person_id")) {
                Object v = f.facts.get(k);
                if (v instanceof List<?> l) l.forEach(x -> peopleIds.add(String.valueOf(x)));
                else if (v != null) peopleIds.add(String.valueOf(v));
            }
        }
        ev.fetch("hr.employees", peopleIds, "Who everyone in the findings is (HR)");
        Map<String, Map<String, Object>> people = new LinkedHashMap<>();
        for (Object id : peopleIds) {
            Map<String, Object> p = ev.employee(String.valueOf(id));
            if (p != null) people.put(String.valueOf(id), p);
        }
        String since = Sql.date(firstDay).minusDays(90).toString();
        TreeSet<String> creators = findings.stream().filter(f -> Set.of("D2", "D5").contains(f.detector) && f.open())
                .map(f -> f.fact("created_by")).collect(Collectors.toCollection(TreeSet::new));
        List<Map<String, Object>> leads = new ArrayList<>();
        for (String pid : creators) {
            Map<String, Object> p = people.getOrDefault(pid, Map.of("name", pid, "title", ""));
            String name = String.valueOf(p.get("name"));
            List<Map<String, Object>> created = db.query("SELECT vendor_id, name, created_date FROM @vendors.vendors WHERE created_by = "
                    + lit(pid) + " AND created_date >= " + lit(since), "Lead · vendors " + name + " set up since " + since + " (vendor master)");
            List<Map<String, Object>> requested = db.query("""
                    SELECT i.vendor_id AS vendor_id, v.name AS vendor_name, COUNT(*) AS invoices, SUM(i.amount) AS total
                    FROM @erp.invoices i JOIN @vendors.vendors v ON v.vendor_id = i.vendor_id
                    WHERE i.requested_by = %s
                    GROUP BY i.vendor_id, v.name""".formatted(lit(pid)), "Lead · every invoice " + name + " requested (ERP × vendor master)");
            Map<String, Object> lead = new LinkedHashMap<>();
            lead.put("person", pid);
            lead.put("name", name);
            lead.put("title", p.get("title"));
            lead.put("since", since);
            lead.put("vendors_created", created);
            lead.put("invoices_requested", requested);
            leads.add(lead);
        }
        String detail = leads.stream().map(l -> l.get("name") + " set up " + ((List<?>) l.get("vendors_created")).size()
                + " vendor(s) since " + since + " and requested " + ((List<Map<String, Object>>) l.get("invoices_requested")).stream()
                .mapToLong(r -> ((Number) r.get("invoices")).longValue()).sum() + " invoice(s)").collect(Collectors.joining("; "));
        step.end("people", "Follow the people", detail.isEmpty() ? "No vendor-creation leads to follow" : detail, Map.of("leads", leads));

        // 6. Connect findings that share a person who acted in them (created, requested, matched, self-approved).
        step.begin();
        Map<String, List<Finding>> byPerson = new LinkedHashMap<>();
        for (Finding f : findings) {
            if (!f.open()) continue;
            for (String a : new TreeSet<>(f.actors)) byPerson.computeIfAbsent(a, k -> new ArrayList<>()).add(f);
        }
        List<Map<String, Object>> links = new ArrayList<>();
        byPerson.forEach((pid, group) -> {
            if (group.size() < 2) return;
            Map<String, Object> p = people.getOrDefault(pid, Map.of("name", pid, "title", ""));
            links.add(Map.of("person", pid, "name", p.get("name"), "title", p.get("title"),
                    "findings", group.stream().map(g -> g.id).toList()));
            String names = group.stream().map(g -> g.facts.getOrDefault("vendor", g.title).toString()).collect(Collectors.joining(", "));
            for (Finding f : group) {
                f.verdict = "ESCALATE";
                group.stream().filter(g -> g != f && !f.connectedTo.contains(g.id)).forEach(g -> f.connectedTo.add(g.id));
                f.reasons.add(p.get("name") + " (" + p.get("title") + ") is behind " + group.size() + " findings: " + names + ".");
            }
        });
        step.end("connect", "Connect findings that share a person", links.isEmpty() ? "No shared people"
                : links.stream().map(l -> l.get("name") + " links " + ((List<?>) l.get("findings")).size() + " findings")
                .collect(Collectors.joining("; ")), Map.of("links", links));

        // 7. Explain each finding, citing its rows.
        step.begin();
        Set<String> directory = new HashSet<>();
        db.query("SELECT name FROM @hr.employees", "Directory of names, for the claim check (HR)").forEach(r -> directory.add(str(r, "name")));
        db.query("SELECT name FROM @vendors.vendors", "Directory of vendors (vendor master)").forEach(r -> directory.add(str(r, "name")));
        findings.parallelStream().forEach(f -> Explain.explain(f, ev, model, directory));
        long byModel = findings.stream().filter(f -> !f.explainedBy.equals("verifier")).count();
        step.end("explain", "Explain each finding with its rows", model == null
                ? findings.size() + " explanation(s) from the verifier's own sentences"
                : findings.size() + " explanation(s); " + byModel + " written by " + model.label() + " and checked sentence by sentence", Map.of());

        findings.sort(Comparator.<Finding>comparingInt(f -> ORDER.get(f.verdict)).thenComparing(f -> -f.amount));
        List<Finding> open = findings.stream().filter(Finding::open).toList();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("at_risk", Math.round(open.stream().mapToDouble(f -> f.amount).sum() * 100) / 100.0);
        summary.put("open", open.size());
        summary.put("escalated", findings.stream().filter(f -> f.verdict.equals("ESCALATE")).count());
        summary.put("remembered", findings.stream().filter(f -> f.verdict.equals("REMEMBERED")).count());
        summary.put("cleared", findings.stream().filter(f -> f.verdict.equals("CLEAR")).count());
        summary.put("records", records);
        summary.put("counts", counts);
        summary.put("period", List.of(firstDay, lastDay));
        summary.put("queries", db.log.size());
        summary.put("seconds", Math.round((System.nanoTime() - started) / 10_000_000.0) / 100.0);
        summary.put("backend", db.label());
        summary.put("memory", memory.kind());
        summary.put("model", model == null ? null : model.label());
        Map<String, Object> evidence = new LinkedHashMap<>();
        findings.forEach(f -> f.refs.forEach(r -> {
            if (ev.get(r) != null) evidence.put(r, mask(ev.get(r)));
        }));
        Map<String, Object> publicPeople = new LinkedHashMap<>();
        people.forEach((k, v) -> publicPeople.put(k, mask(v)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "result");
        result.put("summary", summary);
        result.put("findings", findings);
        result.put("evidence", evidence);
        result.put("people", publicPeople);
        result.put("leads", leads);
        result.put("links", links);
        emit.accept(result);
        return result;
    }

    /** Collects the queries a step ran and sends the step to the trace. */
    private static final class Step {
        private final Backend db;
        private final Consumer<Map<String, Object>> emit;
        private int since;

        Step(Backend db, Consumer<Map<String, Object>> emit) {
            this.db = db;
            this.emit = emit;
        }

        void begin() {
            since = db.log.size();
        }

        void end(String id, String title, String detail, Map<String, Object> extra) {
            List<Map<String, Object>> queries = new ArrayList<>(db.log.subList(since, db.log.size()));
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", "step");
            e.put("id", id);
            e.put("title", title);
            e.put("detail", detail);
            e.put("queries", queries);
            e.put("ms", Math.round(queries.stream().mapToDouble(q -> (double) q.get("ms")).sum() * 10) / 10.0);
            e.putAll(extra);
            emit.accept(e);
        }
    }
}
