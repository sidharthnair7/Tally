package dev.tally.audit;

import static dev.tally.audit.Sql.usd;

import dev.tally.data.Env;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns a finding into two or three plain sentences for the controller, each citing the rows behind it.
 *
 * The verifier's sentences are built from the finding's facts, so they always match the rows. A model can reword
 * them through any OpenAI-compatible endpoint (NVIDIA Nemotron, or Meterless Nightshift on your own machine).
 * Every sentence the model returns is checked against the rows it cites; if one fails, the verifier's sentences
 * are shown instead, and the page says so. The verdict never comes from the model.
 */
public final class Explain {

    public record Model(String base, String name, String key, String label) {
        public static Model from(Env env) {
            String base = env.get("TALLY_LLM_BASE_URL"), name = env.get("TALLY_LLM_MODEL");
            if (base == null || name == null) return null;
            return new Model(base.replaceAll("/+$", ""), name, env.get("TALLY_LLM_API_KEY", ""), env.get("TALLY_LLM_LABEL", name));
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final List<String> MONTHS = List.of("january", "february", "march", "april", "may", "june", "july",
            "august", "september", "october", "november", "december");
    private static final Pattern MONEY = Pattern.compile("\\$\\s?\\d[\\d,]*(?:\\.\\d{1,2})?");
    private static final Pattern ISO_DATE = Pattern.compile("\\b20\\d\\d-\\d\\d-\\d\\d\\b");
    private static final Pattern WORD_DATE = Pattern.compile("\\b(January|February|March|April|May|June|July|August|September|"
            + "October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?(?:,?\\s+(20\\d\\d))?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENT = Pattern.compile("\\b[A-Z]{1,4}-\\d{1,6}(?:-[A-Z])?\\b");
    private static final Pattern INT = Pattern.compile("(?<![\\w.,$-])\\d+(?![\\w-]|[.,]\\d)");
    private static final Pattern REF = Pattern.compile("\\[([a-z]+\\.[a-z]+:[^\\]\\s]+)]");
    private static final String PROMPT = """
            You write for a company controller. Rewrite the verified sentences below into two or three short, plain
            sentences. Use only these facts. Copy every amount, date, invoice number and name exactly as written. Do not
            add anything that is not in the facts. After each sentence, list the references it relies on, chosen only from
            the references given.

            Reply with JSON only, in this shape: {"sentences": [{"text": "...", "refs": ["system.table:key"]}]}""";

    private Explain() {
    }

    static Map<String, Object> sentence(String text, String... refs) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("text", text);
        s.put("refs", Arrays.stream(refs).filter(r -> r != null && !r.isEmpty()).toList());
        return s;
    }

    static Map<String, Object> sentence(String text, List<String> refs) {
        return sentence(text, refs.toArray(String[]::new));
    }

    private static String person(Evidence ev, String id) {
        Map<String, Object> r = ev.employee(id);
        return r == null ? id : r.get("name") + ", " + r.get("title");
    }

    /** The verifier's own sentences, straight from the finding's facts. */
    public static List<Map<String, Object>> template(Finding f, Evidence ev) {
        Map<String, Object> x = f.facts;
        String vendor = f.fact("vendor");
        List<Map<String, Object>> out = new ArrayList<>();
        switch (f.detector) {
            case "D1" -> {
                String amt = usd((double) x.get("amount"));
                out.add(sentence(vendor + " was paid " + amt + " on " + x.get("first_paid") + " and the same " + amt + " again on "
                        + x.get("second_paid") + ", " + x.get("gap_days") + " days later.",
                        "erp.payments:" + x.get("first_payment"), "erp.payments:" + x.get("second_payment")));
                if ((boolean) x.get("near_identical")) {
                    out.add(sentence("The second payment was for invoice " + x.get("second_invoice_no") + ", a resubmitted copy of invoice "
                            + x.get("first_invoice_no") + ".", "erp.invoices:" + x.get("second_invoice"), "erp.invoices:" + x.get("first_invoice")));
                } else {
                    out.add(sentence("The payments were for invoices " + x.get("first_invoice_no") + " and " + x.get("second_invoice_no") + ".",
                            "erp.invoices:" + x.get("first_invoice"), "erp.invoices:" + x.get("second_invoice")));
                }
                out.add(sentence("Equal payments to one vendor within " + x.get("window_days") + " days are the classic duplicate, so ask "
                        + vendor + " to confirm and refund one.", "erp.policies:duplicate_window_days"));
            }
            case "D2" -> {
                if (f.verdict.equals("CLEAR")) {
                    out.add(sentence(vendor + " shares an " + x.get("matched_on") + " with " + x.get("employee") + ", " + x.get("employee_title") + ".",
                            "vendors.vendors:" + f.vendorId, "hr.employees:" + x.get("employee_id")));
                    out.add(sentence("The vendor master records a disclosed conflict of interest, approved by " + x.get("approver") + ", "
                            + x.get("approver_title") + ", so Tally clears it.", "vendors.vendors:" + f.vendorId));
                } else {
                    out.add(sentence(vendor + " is paid into the same " + x.get("matched_on") + " as " + x.get("employee") + ", "
                            + x.get("employee_title") + ".", "vendors.vendors:" + f.vendorId, "hr.employees:" + x.get("employee_id")));
                    boolean same = x.get("created_by").equals(x.get("employee_id"));
                    out.add(sentence(x.get("created_by_name") + " created the vendor record on " + x.get("created_date")
                                    + (same ? ", so the employee set up a vendor that pays them." : "."),
                            "vendors.vendors:" + f.vendorId, "hr.employees:" + x.get("created_by")));
                    List<String> payRefs = f.refs.stream().filter(r -> r.startsWith("erp.payments:")).toList();
                    out.add(sentence("It received " + usd((double) x.get("total")) + " across " + x.get("payments") + " payments this quarter"
                            + ((boolean) x.get("just_under_limit") ? ", each just under the " + usd((double) x.get("limit")) + " approval limit." : "."), payRefs));
                }
            }
            case "D3" -> {
                @SuppressWarnings("unchecked")
                List<Double> amounts = (List<Double>) x.get("amounts");
                List<String> refs = new ArrayList<>(f.refs.stream().filter(r -> r.startsWith("erp.invoices:")).toList());
                refs.add("hr.employees:" + x.get("requested_by"));
                out.add(sentence("On " + x.get("date") + ", " + person(ev, f.fact("requested_by")) + " requested " + x.get("invoices")
                        + " invoices from " + vendor + ": " + String.join(", ", amounts.stream().map(Sql::usd).toList()) + ".", refs));
                out.add(sentence("Each is under the " + usd((double) x.get("limit")) + " approval limit, but together they come to "
                        + usd((double) x.get("total")) + ", which as one purchase needs the Controller's approval.", "erp.policies:ap_approval_limit"));
            }
            case "D4" -> {
                String txn = f.refs.getFirst();
                out.add(sentence("The bank shows a " + f.fact("type").toLowerCase() + " of " + usd((double) x.get("amount")) + " to "
                        + x.get("counterparty") + " on " + x.get("date") + ", and no payment in the ERP matches it.", txn));
                if (x.get("known_vendor") == null) {
                    out.add(sentence("The receiving account is not in the vendor master, so nobody approved this payee.", txn));
                }
            }
            case "D5" -> {
                out.add(sentence(vendor + " was added to the vendor master on " + x.get("created_date") + " by "
                        + person(ev, f.fact("created_by")) + ".", "vendors.vendors:" + f.vendorId, "hr.employees:" + x.get("created_by")));
                out.add(sentence(x.get("age_days") + " days later, on " + x.get("paid_date") + ", it was paid " + usd((double) x.get("amount"))
                        + " for invoice " + x.get("invoice_no") + ".", "erp.payments:" + x.get("payment_id"), "erp.invoices:" + x.get("invoice_id")));
                if ((boolean) x.get("same_person")) {
                    out.add(sentence("The person who created the vendor also requested the payment.", "erp.invoices:" + x.get("invoice_id")));
                }
            }
            case "D6" -> {
                String inv = "erp.invoices:" + x.get("invoice_id");
                out.add(sentence("Invoice " + x.get("invoice_no") + " from " + vendor + " for " + usd((double) x.get("amount"))
                        + " was requested and approved by the same person, " + x.get("person") + ", " + x.get("title") + ".",
                        inv, "hr.employees:" + x.get("person_id")));
                Map<String, Object> row = ev.get(inv);
                if (row != null && "Paid".equals(row.get("status"))) {
                    out.add(sentence("It was paid without anyone else reviewing it.", inv));
                }
            }
            default -> out.add(sentence(f.title, f.refs.getFirst()));
        }
        return out;
    }

    // The claim check ----------------------------------------------------------------------------------------
    public record Allowed(Set<Double> money, Set<Long> ints, Set<String> text, Set<String> dates, Set<String> ids) {
    }

    private static void values(Object o, Set<Object> out) {
        if (o instanceof Map<?, ?> m) m.values().forEach(v -> values(v, out));
        else if (o instanceof Collection<?> c) c.forEach(v -> values(v, out));
        else if (o != null) out.add(o);
    }

    public static Allowed allowed(Finding f, Evidence ev) {
        Set<Object> vals = new HashSet<>();
        values(f.facts, vals);
        f.refs.forEach(r -> values(ev.get(r), vals));
        Set<Double> money = new HashSet<>();
        Set<String> text = new HashSet<>();
        for (Object v : vals) {
            if (v instanceof Number n) money.add(Math.round(n.doubleValue() * 100) / 100.0);
            else if (v instanceof String s) text.add(s);
        }
        Set<Long> ints = new HashSet<>();
        money.stream().filter(m -> m == Math.floor(m)).forEach(m -> ints.add(m.longValue()));
        ints.add((long) f.refs.size());
        Set<String> dates = new HashSet<>(), ids = new HashSet<>();
        for (String t : text) {
            if (ISO_DATE.matcher(t).matches()) dates.add(t);
            if (IDENT.matcher(t).matches()) ids.add(t);
        }
        return new Allowed(money, ints, text, dates, ids);
    }

    /** Null when every claim in the sentence is in its cited rows; otherwise the first claim that is not. */
    public static String checkSentence(String text, List<String> refs, Finding f, Allowed ok, Set<String> directory) {
        if (refs.isEmpty() || !f.refs.containsAll(refs)) return "cites rows that are not behind this finding";
        Matcher m = MONEY.matcher(text);
        while (m.find()) {
            double v = Double.parseDouble(m.group().replace("$", "").replace(",", "").strip());
            if (!ok.money().contains(Math.round(v * 100) / 100.0)) return "amount " + m.group() + " is not in the rows";
        }
        String rest = MONEY.matcher(text).replaceAll(" ");
        m = ISO_DATE.matcher(rest);
        while (m.find()) {
            if (!ok.dates().contains(m.group())) return "date " + m.group() + " is not in the rows";
        }
        rest = ISO_DATE.matcher(rest).replaceAll(" ");
        m = WORD_DATE.matcher(rest);
        while (m.find()) {
            String prefix = m.group(1).toLowerCase().substring(0, 3);
            int month = 1 + MONTHS.indexOf(MONTHS.stream().filter(n -> n.startsWith(prefix)).findFirst().orElseThrow());
            String iso = "%s-%02d-%02d".formatted(m.group(3) != null ? m.group(3) : "2026", month, Integer.parseInt(m.group(2)));
            if (!ok.dates().contains(iso)) return "date " + m.group() + " is not in the rows";
        }
        rest = WORD_DATE.matcher(rest).replaceAll(" ");
        m = IDENT.matcher(rest);
        while (m.find()) {
            if (!ok.ids().contains(m.group())) return m.group() + " is not in the rows";
        }
        rest = IDENT.matcher(rest).replaceAll(" ");
        m = INT.matcher(rest);
        while (m.find()) {
            if (!ok.ints().contains(Long.parseLong(m.group()))) return "the number " + m.group() + " is not in the rows";
        }
        for (String name : directory) {
            if (text.contains(name) && !ok.text().contains(name)) return name + " is not in the rows";
        }
        return null;
    }

    // The model ----------------------------------------------------------------------------------------------
    static List<Map<String, Object>> rewrite(Finding f, List<Map<String, Object>> sentences, Model model) throws Exception {
        Map<String, Object> payload = Map.of("finding", f.title, "verified_sentences", sentences, "facts", f.facts, "references", f.refs);
        String system = (model.name().toLowerCase().contains("nemotron") ? "/no_think\n" : "") + PROMPT;
        Map<String, Object> body = Map.of("model", model.name(), "temperature", 0.2, "max_tokens", 500, "messages", List.of(
                Map.of("role", "system", "content", system), Map.of("role", "user", "content", JSON.writeValueAsString(payload))));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(model.base() + "/chat/completions"))
                .timeout(Duration.ofSeconds(45)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (!model.key().isEmpty()) req.header("Authorization", "Bearer " + model.key());
        HttpResponse<String> res = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) throw new IllegalStateException("model HTTP " + res.statusCode());
        String text = JSON.readTree(res.body()).path("choices").path(0).path("message").path("content").asString("");
        text = text.replaceAll("(?s)<think>.*?</think>", "").strip();
        text = text.substring(text.indexOf('{'), text.lastIndexOf('}') + 1);
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode s : JSON.readTree(text).path("sentences")) {
            String t = s.path("text").asString("").strip();
            if (t.isEmpty()) continue;
            List<String> refs = new ArrayList<>();
            s.path("refs").forEach(r -> refs.add(r.asString()));
            Matcher m = REF.matcher(t);
            while (refs.isEmpty() && m.find()) refs.add(m.group(1));
            out.add(sentence(REF.matcher(t).replaceAll("").strip(), refs));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static void explain(Finding f, Evidence ev, Model model, Set<String> directory) {
        List<Map<String, Object>> base = template(f, ev);
        f.explanation = base;
        f.explainedBy = "verifier";
        if (model == null || f.verdict.equals("CLEAR")) return;
        List<Map<String, Object>> candidate;
        try {
            candidate = rewrite(f, base, model);
        } catch (Exception e) {
            f.reasons.add("Model unavailable (" + e.getClass().getSimpleName() + "); showing the verifier's sentences.");
            return;
        }
        Allowed ok = allowed(f, ev);
        for (Map<String, Object> s : candidate) {
            String problem = checkSentence((String) s.get("text"), (List<String>) s.get("refs"), f, ok, directory);
            if (problem != null) {
                f.reasons.add("The model's sentence failed the evidence check (" + problem + "); showing the verifier's sentences instead.");
                return;
            }
        }
        if (!candidate.isEmpty()) {
            f.explanation = candidate;
            f.explainedBy = model.label();
        }
    }
}
