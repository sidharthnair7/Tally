package dev.tally.audit;

import static dev.tally.audit.Sql.day;
import static dev.tally.audit.Sql.daysBetween;
import static dev.tally.audit.Sql.lit;
import static dev.tally.audit.Sql.list;
import static dev.tally.audit.Sql.num;
import static dev.tally.audit.Sql.str;
import static dev.tally.audit.Sql.usd;

import dev.tally.data.Backend;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The six checks. Each one gathers evidence with SQL that joins the company's separate systems, then a plain
 * rule decides. The SQL finds candidates; the rule, never a model, decides what becomes a finding.
 */
public final class Checks {

    public record Result(List<Finding> found, String note) {
    }

    public interface Check {
        Result run(Backend db, Evidence ev, Map<String, Object> policy);
    }

    public record Named(String code, String name, Check check) {
    }

    /** The order the audit runs them: the ones most likely to be fraud first. */
    public static final List<Named> ALL = List.of(
            new Named("D2", "Vendor matches an employee", Checks::vendorMatchesEmployee),
            new Named("D5", "New vendor, large payment", Checks::newVendorBigPayment),
            new Named("D4", "Payment not in the books", Checks::unrecordedBankPayments),
            new Named("D1", "Duplicate payment", Checks::duplicatePayments),
            new Named("D3", "Split to dodge approval", Checks::splitInvoices),
            new Named("D6", "Approved own invoice", Checks::selfApproved));

    private Checks() {
    }

    static Map<String, Object> facts(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static double policy(Map<String, Object> policy, String key) {
        return num(policy.get(key));
    }

    // D1 ----------------------------------------------------------------------------------------------------
    static Result duplicatePayments(Backend db, Evidence ev, Map<String, Object> policy) {
        int window = (int) policy(policy, "duplicate_window_days");
        List<Map<String, Object>> rows = db.query("""
                SELECT p1.payment_id AS first_payment, p2.payment_id AS second_payment, p1.vendor_id AS vendor_id,
                       v.name AS vendor_name, p1.amount AS amount, p1.paid_date AS first_paid, p2.paid_date AS second_paid,
                       i1.invoice_id AS first_invoice, i1.invoice_no AS first_invoice_no,
                       i2.invoice_id AS second_invoice, i2.invoice_no AS second_invoice_no, i2.requested_by AS requested_by
                FROM @erp.payments p1
                JOIN @erp.payments p2 ON p2.vendor_id = p1.vendor_id AND p2.amount = p1.amount AND p2.payment_id > p1.payment_id
                JOIN @erp.invoices i1 ON i1.invoice_id = p1.invoice_id
                JOIN @erp.invoices i2 ON i2.invoice_id = p2.invoice_id
                JOIN @vendors.vendors v ON v.vendor_id = p1.vendor_id""", "D1 · equal payments to the same vendor (ERP × vendor master)");
        List<Map<String, Object>> hits = rows.stream().filter(r -> {
            long gap = daysBetween(r.get("first_paid"), r.get("second_paid"));
            return gap >= 0 && gap <= window;
        }).toList();
        List<Object> pays = new ArrayList<>(), invs = new ArrayList<>(), vends = new ArrayList<>();
        for (var r : hits) {
            pays.add(r.get("first_payment"));
            pays.add(r.get("second_payment"));
            invs.add(r.get("first_invoice"));
            invs.add(r.get("second_invoice"));
            vends.add(r.get("vendor_id"));
        }
        ev.fetch("erp.payments", pays, "D1 · evidence");
        ev.fetch("erp.invoices", invs, "D1 · evidence");
        ev.fetch("vendors.vendors", vends, "D1 · evidence");
        List<Finding> out = new ArrayList<>();
        for (var r : hits) {
            long gap = daysBetween(r.get("first_paid"), r.get("second_paid"));
            String a = str(r, "first_invoice_no"), b = str(r, "second_invoice_no");
            double amount = num(r.get("amount"));
            out.add(new Finding("D1:" + r.get("second_payment"), "D1", "Duplicate payment",
                    r.get("vendor_name") + " was paid " + usd(amount) + " twice in " + gap + " days", amount, str(r, "vendor_id"),
                    facts("vendor", r.get("vendor_name"), "amount", amount, "gap_days", gap, "window_days", window,
                            "first_payment", r.get("first_payment"), "second_payment", r.get("second_payment"),
                            "first_paid", day(r.get("first_paid")), "second_paid", day(r.get("second_paid")),
                            "first_invoice", r.get("first_invoice"), "second_invoice", r.get("second_invoice"),
                            "first_invoice_no", a, "second_invoice_no", b,
                            "near_identical", b.startsWith(a) || a.startsWith(b), "requested_by", r.get("requested_by")),
                    List.of("erp.payments:" + r.get("first_payment"), "erp.payments:" + r.get("second_payment"),
                            "erp.invoices:" + r.get("first_invoice"), "erp.invoices:" + r.get("second_invoice"),
                            "vendors.vendors:" + r.get("vendor_id"), "erp.policies:duplicate_window_days"),
                    List.of(str(r, "requested_by")), List.of("erp", "vendors")));
        }
        return new Result(out, rows.size() + " equal-amount pairs; " + hits.size() + " paid within " + window + " days of each other");
    }

    // D2 ----------------------------------------------------------------------------------------------------
    static Result vendorMatchesEmployee(Backend db, Evidence ev, Map<String, Object> policy) {
        List<Map<String, Object>> rows = db.query("""
                SELECT v.vendor_id AS vendor_id, v.name AS vendor_name, v.created_by AS created_by, v.created_date AS created_date,
                       v.coi_disclosed AS coi_disclosed, v.coi_approved_by AS coi_approved_by,
                       e.employee_id AS employee_id, e.name AS employee_name, e.title AS employee_title,
                       CASE WHEN v.bank_account = e.bank_account THEN 'bank account' ELSE 'address' END AS matched_on
                FROM @vendors.vendors v
                JOIN @hr.employees e ON v.bank_account = e.bank_account OR v.address = e.home_address""",
                "D2 · vendors that share a bank account or address with an employee (vendor master × HR)");
        List<Finding> out = new ArrayList<>();
        if (rows.isEmpty()) return new Result(out, "no vendor shares a bank account or address with an employee");
        List<Object> vids = rows.stream().map(r -> r.get("vendor_id")).toList();
        List<Map<String, Object>> pays = db.query("SELECT payment_id, invoice_id, vendor_id, amount, paid_date FROM @erp.payments "
                + "WHERE vendor_id IN " + list(vids), "D2 · what those vendors were paid (ERP)");
        ev.add("erp.payments", pays);
        ev.fetch("vendors.vendors", vids, "D2 · evidence");
        Set<Object> people = new TreeSet<>();
        for (var r : rows) {
            people.add(r.get("employee_id"));
            people.add(r.get("created_by"));
            if (r.get("coi_approved_by") != null) people.add(r.get("coi_approved_by"));
        }
        ev.fetch("hr.employees", people, "D2 · who these people are (HR)");
        double limit = policy(policy, "ap_approval_limit");
        for (var r : rows) {
            String vid = str(r, "vendor_id"), eid = str(r, "employee_id"), createdBy = str(r, "created_by");
            List<Map<String, Object>> mine = pays.stream().filter(p -> vid.equals(str(p, "vendor_id")))
                    .sorted(Comparator.comparing(p -> str(p, "paid_date"))).toList();
            double total = num(mine.stream().mapToDouble(p -> num(p.get("amount"))).sum());
            List<String> invRefs = ev.fetch("erp.invoices", mine.stream().map(p -> p.get("invoice_id")).toList(), "D2 · evidence");
            long under = mine.stream().filter(p -> num(p.get("amount")) >= 0.9 * limit && num(p.get("amount")) < limit).count();
            Map<String, Object> creator = ev.employee(createdBy);
            List<String> refs = new ArrayList<>(List.of("vendors.vendors:" + vid, "hr.employees:" + eid));
            mine.forEach(p -> refs.add("erp.payments:" + p.get("payment_id")));
            refs.addAll(invRefs);
            Finding f = new Finding("D2:" + vid, "D2", "Vendor matches an employee",
                    r.get("vendor_name") + " is paid into " + r.get("employee_name") + "'s " + r.get("matched_on"), total, vid,
                    facts("vendor", r.get("vendor_name"), "employee", r.get("employee_name"), "employee_id", eid,
                            "employee_title", r.get("employee_title"), "matched_on", r.get("matched_on"),
                            "created_by", createdBy, "created_by_name", creator != null ? creator.get("name") : createdBy,
                            "created_date", day(r.get("created_date")), "payments", mine.size(), "total", total,
                            "just_under_limit", under == mine.size() && mine.size() > 1, "limit", limit),
                    refs, new ArrayList<>(new TreeSet<>(List.of(eid, createdBy))), List.of("vendors", "hr", "erp"));
            String approverId = str(r, "coi_approved_by");
            Map<String, Object> approver = approverId == null ? null : ev.employee(approverId);
            boolean disclosed = Set.of("yes", "true", "1").contains(String.valueOf(r.get("coi_disclosed")).toLowerCase());
            if (disclosed && approver != null && !approverId.equals(eid)
                    && Set.of("Controller", "Chief Financial Officer").contains(str(approver, "title"))) {
                f.verdict = "CLEAR";
                f.title = r.get("vendor_name") + " shares an " + r.get("matched_on") + " with " + r.get("employee_name") + ", disclosed and approved";
                f.facts.put("approver", approver.get("name"));
                f.facts.put("approver_title", approver.get("title"));
                f.refs.add("hr.employees:" + approverId);
                f.reasons.add("The vendor master records a disclosed conflict of interest, approved by "
                        + approver.get("name") + " (" + approver.get("title") + ").");
            }
            out.add(f);
        }
        return new Result(out, rows.size() + " vendor(s) share a bank account or address with an employee");
    }

    // D3 ----------------------------------------------------------------------------------------------------
    static Result splitInvoices(Backend db, Evidence ev, Map<String, Object> policy) {
        double limit = policy(policy, "ap_approval_limit");
        String lim = String.format("%.0f", limit);
        List<Map<String, Object>> rows = db.query("""
                SELECT i.vendor_id AS vendor_id, v.name AS vendor_name, i.requested_by AS requested_by, i.invoice_date AS invoice_date,
                       COUNT(*) AS invoices, SUM(i.amount) AS total
                FROM @erp.invoices i
                JOIN @vendors.vendors v ON v.vendor_id = i.vendor_id
                WHERE i.amount < %s
                GROUP BY i.vendor_id, v.name, i.requested_by, i.invoice_date
                HAVING COUNT(*) >= 2 AND SUM(i.amount) >= %s""".formatted(lim, lim),
                "D3 · same-day invoices that each dodge the approval limit (ERP × vendor master)");
        List<Finding> out = new ArrayList<>();
        for (var r : rows) {
            String day = day(r.get("invoice_date"));
            List<Map<String, Object>> invs = new ArrayList<>(db.query("SELECT * FROM @erp.invoices WHERE vendor_id = " + lit(r.get("vendor_id"))
                    + " AND requested_by = " + lit(r.get("requested_by")) + " AND invoice_date = " + lit(day)
                    + " AND amount < " + lim, "D3 · evidence"));
            ev.add("erp.invoices", invs);
            ev.fetch("hr.employees", List.of(r.get("requested_by")), "D3 · who requested them (HR)");
            ev.fetch("vendors.vendors", List.of(r.get("vendor_id")), "D3 · evidence");
            invs.sort(Comparator.comparing(i -> str(i, "invoice_no")));
            List<String> refs = new ArrayList<>();
            invs.forEach(i -> refs.add("erp.invoices:" + i.get("invoice_id")));
            refs.add("hr.employees:" + r.get("requested_by"));
            refs.add("erp.policies:ap_approval_limit");
            int n = ((Number) r.get("invoices")).intValue();
            double total = num(r.get("total"));
            out.add(new Finding("D3:" + r.get("vendor_id") + ":" + day, "D3", "Split to dodge approval",
                    n + " invoices from " + r.get("vendor_name") + " on one day, each under " + usd(limit), total, str(r, "vendor_id"),
                    facts("vendor", r.get("vendor_name"), "requested_by", r.get("requested_by"), "date", day, "invoices", n,
                            "total", total, "limit", limit, "amounts", invs.stream().map(i -> num(i.get("amount"))).toList(),
                            "invoice_nos", invs.stream().map(i -> i.get("invoice_no")).toList(),
                            "approved_by", new ArrayList<>(new TreeSet<>(invs.stream().map(i -> str(i, "approved_by")).toList()))),
                    refs, List.of(str(r, "requested_by")), List.of("erp", "vendors", "hr")));
        }
        return new Result(out, rows.size() + " same-day group(s) under the " + usd(limit) + " limit that add up past it");
    }

    // D4 ----------------------------------------------------------------------------------------------------
    static Result unrecordedBankPayments(Backend db, Evidence ev, Map<String, Object> policy) {
        List<Map<String, Object>> rows = db.query("""
                SELECT t.txn_id AS txn_id, t.posted_date AS posted_date, t.txn_type AS txn_type, t.amount AS amount,
                       t.counterparty_name AS counterparty_name, t.counterparty_account AS counterparty_account,
                       t.reference AS reference, v.vendor_id AS known_vendor
                FROM @bank.transactions t
                LEFT JOIN @erp.payments p ON p.payment_id = t.reference
                LEFT JOIN @vendors.vendors v ON v.bank_account = t.counterparty_account
                WHERE t.direction = 'debit' AND t.txn_type IN ('ACH', 'WIRE', 'CHECK') AND p.payment_id IS NULL""",
                "D4 · money that left the bank with no payment in the ERP (bank feed × ERP × vendor master)");
        ev.fetch("bank.transactions", rows.stream().map(r -> r.get("txn_id")).toList(), "D4 · evidence");
        List<Finding> out = new ArrayList<>();
        for (var r : rows) {
            double amount = num(r.get("amount"));
            out.add(new Finding("D4:" + r.get("txn_id"), "D4", "Payment not in the books",
                    usd(amount) + " " + str(r, "txn_type").toLowerCase() + " to " + r.get("counterparty_name") + " is not in the ERP",
                    amount, str(r, "known_vendor"),
                    facts("counterparty", r.get("counterparty_name"), "counterparty_account", r.get("counterparty_account"),
                            "amount", amount, "date", day(r.get("posted_date")), "type", r.get("txn_type"),
                            "reference", r.get("reference"), "known_vendor", r.get("known_vendor")),
                    List.of("bank.transactions:" + r.get("txn_id")), List.of(), List.of("bank", "erp", "vendors")));
        }
        return new Result(out, rows.size() + " bank debit(s) with no matching ERP payment");
    }

    // D5 ----------------------------------------------------------------------------------------------------
    static Result newVendorBigPayment(Backend db, Evidence ev, Map<String, Object> policy) {
        double big = policy(policy, "cfo_approval_limit");
        int days = (int) policy(policy, "new_vendor_days");
        List<Map<String, Object>> rows = db.query("""
                SELECT v.vendor_id AS vendor_id, v.name AS vendor_name, v.created_date AS created_date, v.created_by AS created_by,
                       p.payment_id AS payment_id, p.amount AS amount, p.paid_date AS paid_date,
                       i.invoice_id AS invoice_id, i.invoice_no AS invoice_no, i.requested_by AS requested_by, i.approved_by AS approved_by
                FROM @vendors.vendors v
                JOIN @erp.payments p ON p.vendor_id = v.vendor_id
                JOIN @erp.invoices i ON i.invoice_id = p.invoice_id
                WHERE p.amount >= %.0f""".formatted(big), "D5 · large payments, with the age of the vendor that got them (vendor master × ERP)");
        List<Map<String, Object>> hits = rows.stream().filter(r -> {
            long age = daysBetween(r.get("created_date"), r.get("paid_date"));
            return age >= 0 && age <= days;
        }).toList();
        ev.fetch("vendors.vendors", hits.stream().map(r -> r.get("vendor_id")).toList(), "D5 · evidence");
        ev.fetch("erp.payments", hits.stream().map(r -> r.get("payment_id")).toList(), "D5 · evidence");
        ev.fetch("erp.invoices", hits.stream().map(r -> r.get("invoice_id")).toList(), "D5 · evidence");
        List<Object> people = new ArrayList<>();
        hits.forEach(r -> people.addAll(List.of(r.get("created_by"), r.get("requested_by"), r.get("approved_by"))));
        ev.fetch("hr.employees", people, "D5 · who set up the vendor and requested the payment (HR)");
        List<Finding> out = new ArrayList<>();
        for (var r : hits) {
            long age = daysBetween(r.get("created_date"), r.get("paid_date"));
            double amount = num(r.get("amount"));
            String createdBy = str(r, "created_by"), requestedBy = str(r, "requested_by");
            out.add(new Finding("D5:" + r.get("payment_id"), "D5", "New vendor, large payment",
                    r.get("vendor_name") + " was paid " + usd(amount) + " " + age + " days after it was created", amount, str(r, "vendor_id"),
                    facts("vendor", r.get("vendor_name"), "amount", amount, "age_days", age, "days", days,
                            "created_date", day(r.get("created_date")), "paid_date", day(r.get("paid_date")),
                            "created_by", createdBy, "requested_by", requestedBy, "approved_by", r.get("approved_by"),
                            "payment_id", r.get("payment_id"), "invoice_id", r.get("invoice_id"), "invoice_no", r.get("invoice_no"),
                            "same_person", createdBy.equals(requestedBy)),
                    List.of("vendors.vendors:" + r.get("vendor_id"), "erp.payments:" + r.get("payment_id"),
                            "erp.invoices:" + r.get("invoice_id"), "hr.employees:" + createdBy),
                    new ArrayList<>(new TreeSet<>(List.of(createdBy, requestedBy))), List.of("vendors", "erp", "hr")));
        }
        return new Result(out, rows.size() + " payment(s) of " + usd(big) + " or more; " + hits.size() + " to a vendor under " + days + " days old");
    }

    // D6 ----------------------------------------------------------------------------------------------------
    static Result selfApproved(Backend db, Evidence ev, Map<String, Object> policy) {
        List<Map<String, Object>> rows = db.query("""
                SELECT i.invoice_id AS invoice_id, i.invoice_no AS invoice_no, i.vendor_id AS vendor_id, v.name AS vendor_name,
                       i.amount AS amount, i.invoice_date AS invoice_date, i.requested_by AS requested_by, e.name AS approver_name,
                       e.title AS approver_title
                FROM @erp.invoices i
                JOIN @vendors.vendors v ON v.vendor_id = i.vendor_id
                JOIN @hr.employees e ON e.employee_id = i.approved_by
                WHERE i.requested_by = i.approved_by""", "D6 · invoices approved by the person who requested them (ERP × HR)");
        ev.fetch("erp.invoices", rows.stream().map(r -> r.get("invoice_id")).toList(), "D6 · evidence");
        ev.fetch("hr.employees", rows.stream().map(r -> r.get("requested_by")).toList(), "D6 · evidence");
        List<Finding> out = new ArrayList<>();
        for (var r : rows) {
            double amount = num(r.get("amount"));
            out.add(new Finding("D6:" + r.get("invoice_id"), "D6", "Approved own invoice",
                    r.get("approver_name") + " approved their own " + usd(amount) + " invoice from " + r.get("vendor_name"),
                    amount, str(r, "vendor_id"),
                    facts("vendor", r.get("vendor_name"), "amount", amount, "invoice_id", r.get("invoice_id"),
                            "invoice_no", r.get("invoice_no"), "date", day(r.get("invoice_date")),
                            "person", r.get("approver_name"), "person_id", r.get("requested_by"), "title", r.get("approver_title")),
                    List.of("erp.invoices:" + r.get("invoice_id"), "hr.employees:" + r.get("requested_by")),
                    List.of(str(r, "requested_by")), List.of("erp", "vendors", "hr")));
        }
        return new Result(out, rows.size() + " invoice(s) approved by their own requester");
    }
}
