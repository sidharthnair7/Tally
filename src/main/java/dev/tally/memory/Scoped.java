package dev.tally.memory;

import java.util.List;
import java.util.Map;

/**
 * The memory narrowed to one visitor's decisions, by prefixing every fingerprint with their workspace. On a shared,
 * hosted demo this means one judge's decisions never change another judge's run. An empty workspace is the shared one.
 */
public class Scoped implements Memory {

    private final Memory inner;
    public final String workspace;

    public Scoped(Memory inner, String workspace) {
        this.inner = inner;
        this.workspace = workspace;
    }

    private String fp(String fingerprint) {
        return workspace.isEmpty() ? fingerprint : workspace + "/" + fingerprint;
    }

    @Override
    public String kind() {
        return inner.kind();
    }

    @Override
    public Map<String, Object> remember(String fingerprint, String decision, String title, String reason, String by) {
        return inner.remember(fp(fingerprint), decision, title, reason, by);
    }

    @Override
    public Map<String, Object> recall(String fingerprint, String title) {
        return inner.recall(fp(fingerprint), title);
    }

    @Override
    public void used(String memoryId) {
        inner.used(memoryId);
    }

    @Override
    public List<Map<String, Object>> all() {
        return inner.all().stream().filter(m -> {
            String fp = String.valueOf(m.getOrDefault("fingerprint", ""));
            return workspace.isEmpty() ? !fp.contains("/") : fp.startsWith(workspace + "/");
        }).toList();
    }
}
