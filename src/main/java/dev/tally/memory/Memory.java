package dev.tally.memory;

import java.util.List;
import java.util.Map;

/**
 * What Tally remembers between runs: the controller's decisions about findings, remembered against the finding's
 * fingerprint (the check plus who was paid), so the same pattern next run is recognised.
 */
public interface Memory {

    String kind();

    Map<String, Object> remember(String fingerprint, String decision, String title, String reason, String by);

    /** The decision on this fingerprint, or null if the controller never made one. */
    Map<String, Object> recall(String fingerprint, String title);

    void used(String memoryId);

    List<Map<String, Object>> all();

    static String text(String fingerprint, String decision, String title, String reason, String by) {
        String what = decision.equals("expected") ? "Expected, not fraud" : "Confirmed fraud";
        return "[tally:" + fingerprint + "] " + what + ": " + title + ". Reason: " + reason + ". Decided by " + by + ".";
    }

    static String decisionOf(Object content) {
        return String.valueOf(content).contains("Expected, not fraud") ? "expected" : "fraud";
    }
}
