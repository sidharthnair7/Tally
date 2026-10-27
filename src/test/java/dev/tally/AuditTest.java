package dev.tally;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.tally.audit.Checks;
import dev.tally.audit.Evidence;
import dev.tally.audit.Explain;
import dev.tally.audit.Finding;
import dev.tally.audit.Investigation;
import dev.tally.data.H2Backend;
import dev.tally.memory.FileMemory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * A full offline audit against the planted answer key: every fraud found, both look-alikes handled, the person
 * behind two findings connected, the controller's decision remembered on the next run, and the claim check.
 */
class AuditTest {

    static final Path SOURCES = Path.of("data", "sources");
    static JsonNode key;
    static Map<String, Object> first;
    static List<Finding> findings;

    @BeforeAll
    static void audit() throws Exception {
        key = new ObjectMapper().readTree(Path.of("data", "answer_key.json").toFile());
        first = run(new FileMemory(Files.createTempDirectory("tally").resolve("memory.json")));
        findings = findings(first);
    }

    static Map<String, Object> run(FileMemory memory) {
        return Investigation.run(new H2Backend(SOURCES), memory, null, e -> { });
    }

    @SuppressWarnings("unchecked")
    static List<Finding> findings(Map<String, Object> result) {
        return (List<Finding>) result.get("findings");
    }

    static boolean isCase(Finding f, JsonNode c) {
        if (!f.detector.equals(c.get("detector").asString())) return false;
        if (c.has("txn_id")) return f.refs.contains("bank.transactions:" + c.get("txn_id").asString());
        return c.get("vendor_id").asString().equals(f.vendorId);
    }

    @Test
    void everyPlantedFraudIsFoundWithItsAmount() {
        for (JsonNode c : key.get("frauds")) {
            List<Finding> hits = findings.stream().filter(f -> f.open() && isCase(f, c)).toList();
            assertEquals(1, hits.size(), c.toString());
            assertEquals(c.get("amount").asDouble(), hits.getFirst().amount, 0.005);
        }
    }

    @Test
    void nothingElseIsFlaggedButTheKnownLookAlike() {
        List<JsonNode> expected = new java.util.ArrayList<>();
        key.get("frauds").forEach(expected::add);
        expected.add(key.get("decoys").get(0));
        List<String> extra = findings.stream().filter(Finding::open)
                .filter(f -> expected.stream().noneMatch(c -> isCase(f, c))).map(f -> f.id).toList();
        assertEquals(List.of(), extra);
        assertEquals(expected.size(), findings.stream().filter(Finding::open).count());
    }

    @Test
    void aDisclosedConflictIsClearedByTheBooks() {
        List<String> verdicts = findings.stream().filter(f -> isCase(f, key.get("decoys").get(1))).map(f -> f.verdict).toList();
        assertEquals(List.of("CLEAR"), verdicts);
    }

    @Test
    @SuppressWarnings("unchecked")
    void onePersonBehindTwoFindingsIsEscalated() {
        List<Map<String, Object>> links = (List<Map<String, Object>>) first.get("links");
        assertEquals(List.of(key.get("connected").get("employee_id").asString()), links.stream().map(l -> l.get("person")).toList());
        Set<String> escalated = findings.stream().filter(f -> f.verdict.equals("ESCALATE")).map(f -> f.vendorId).collect(Collectors.toSet());
        Set<String> want = Set.of(key.get("connected").get("vendor_ids").get(0).asString(), key.get("connected").get("vendor_ids").get(1).asString());
        assertEquals(want, escalated);
    }

    @Test
    @SuppressWarnings("unchecked")
    void everySentenceCitesRowsBehindItsFinding() {
        Map<String, Object> evidence = (Map<String, Object>) first.get("evidence");
        for (Finding f : findings) {
            for (Map<String, Object> s : f.explanation) {
                List<String> refs = (List<String>) s.get("refs");
                assertFalse(refs.isEmpty(), (String) s.get("text"));
                for (String ref : refs) {
                    assertTrue(f.refs.contains(ref), ref);
                    assertTrue(evidence.containsKey(ref), ref);
                }
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void accountNumbersLeaveTheServerMasked() {
        Map<String, Map<String, Object>> evidence = (Map<String, Map<String, Object>>) first.get("evidence");
        for (Map<String, Object> row : evidence.values()) {
            for (String k : List.of("bank_account", "counterparty_account")) {
                Object v = row.get(k);
                if (v != null && !v.toString().isEmpty()) assertTrue(v.toString().startsWith("••••"), v.toString());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theControllersDecisionIsRememberedNextRun() throws Exception {
        FileMemory memory = new FileMemory(Files.createTempDirectory("tally").resolve("memory.json"));
        Map<String, Object> before = run(memory);
        Finding decoy = findings(before).stream().filter(f -> isCase(f, key.get("decoys").get(0))).findFirst().orElseThrow();
        memory.remember(decoy.fingerprint(), "expected", decoy.title, "Split shipment on one PO", "Karen Liu, Controller");
        Map<String, Object> after = run(memory);
        Finding again = findings(after).stream().filter(f -> isCase(f, key.get("decoys").get(0))).findFirst().orElseThrow();
        assertEquals("REMEMBERED", again.verdict);
        double riskBefore = (double) ((Map<String, Object>) before.get("summary")).get("at_risk");
        double riskAfter = (double) ((Map<String, Object>) after.get("summary")).get("at_risk");
        assertEquals(riskBefore - decoy.amount, riskAfter, 0.005);
        for (JsonNode c : key.get("frauds")) {
            assertTrue(findings(after).stream().anyMatch(f -> isCase(f, c) && f.open()), c.toString());
        }
    }

    // The claim check --------------------------------------------------------------------------------------------
    static Finding kessler;
    static Evidence ev;

    static Map<String, Object> verified() {
        H2Backend db = new H2Backend(SOURCES);
        ev = new Evidence(db);
        List<Map<String, Object>> rows = db.query("SELECT * FROM @erp.policies", "policy");
        ev.add("erp.policies", rows);
        Map<String, Object> policy = rows.stream().collect(Collectors.toMap(r -> (String) r.get("policy"), r -> r.get("value")));
        Checks.Named d1 = Checks.ALL.stream().filter(c -> c.code().equals("D1")).findFirst().orElseThrow();
        kessler = d1.check().run(db, ev, policy).found().stream()
                .filter(f -> "Kessler Steel Supply".equals(f.facts.get("vendor"))).findFirst().orElseThrow();
        return Explain.template(kessler, ev).getFirst();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theClaimCheckPassesTruthAndCatchesInventions() {
        Map<String, Object> good = verified();
        String text = (String) good.get("text");
        List<String> refs = (List<String>) good.get("refs");
        Explain.Allowed ok = Explain.allowed(kessler, ev);
        assertNull(Explain.checkSentence(text, refs, kessler, ok, Set.of()));
        assertTrue(Explain.checkSentence(text.replaceFirst("18,450\\.00", "18,540.00"), refs, kessler, ok, Set.of()).contains("amount"));
        assertTrue(Explain.checkSentence("Derek Holloway approved both payments.", refs, kessler, ok, Set.of("Derek Holloway")).contains("Derek Holloway"));
        assertNotNull(Explain.checkSentence(text, List.of("erp.payments:PAY-999999"), kessler, ok, Set.of()));
        String reworded = "Kessler Steel Supply was paid $18,450.00 on August 4 and again on August 11.";
        assertNull(Explain.checkSentence(reworded, refs, kessler, ok, Set.of()));
        assertTrue(Explain.checkSentence(reworded.replace("August 11", "August 12"), refs, kessler, ok, Set.of()).contains("date"));
    }
}
