package dev.tally.audit;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One thing the auditor found. The verdict is decided by rules, the controller's remembered decisions and the
 * connections between findings; never by a model.
 */
public class Finding {

    public final String id;
    public final String detector;
    public final String check;
    public String title;
    public final double amount;
    @JsonProperty("vendor_id")
    public final String vendorId;
    public final Map<String, Object> facts;
    /** Every row behind this finding, as "system.table:key". */
    public final List<String> refs;
    /** Employees who acted in it: created the vendor, requested, matched, self-approved. Never mere approvers. */
    public final List<String> actors;
    public final List<String> systems;
    /** FLAG, ESCALATE, CLEAR (the books explain it) or REMEMBERED (the controller did). */
    public String verdict = "FLAG";
    public final List<String> reasons = new ArrayList<>();
    public Map<String, Object> memory;
    @JsonProperty("connected_to")
    public final List<String> connectedTo = new ArrayList<>();
    public List<Map<String, Object>> explanation = new ArrayList<>();
    @JsonProperty("explained_by")
    public String explainedBy = "";

    public Finding(String id, String detector, String check, String title, double amount, String vendorId,
                   Map<String, Object> facts, List<String> refs, List<String> actors, List<String> systems) {
        this.id = id;
        this.detector = detector;
        this.check = check;
        this.title = title;
        this.amount = amount;
        this.vendorId = vendorId;
        this.facts = new LinkedHashMap<>(facts);
        this.refs = new ArrayList<>(refs);
        this.actors = new ArrayList<>(actors);
        this.systems = systems;
    }

    /** What a controller's decision is remembered against: the check plus who was paid. */
    @JsonProperty("fingerprint")
    public String fingerprint() {
        Object payee = vendorId != null ? vendorId : facts.getOrDefault("counterparty_account", "");
        return detector + "|" + payee;
    }

    public boolean open() {
        return verdict.equals("FLAG") || verdict.equals("ESCALATE");
    }

    public String fact(String key) {
        Object v = facts.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
