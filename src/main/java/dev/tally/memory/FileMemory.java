package dev.tally.memory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** A stand-in with the same behaviour, for tests and for when the H-MEM sidecar is not running. */
public class FileMemory implements Memory {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path path;

    public FileMemory(Path path) {
        this.path = path;
    }

    @Override
    public String kind() {
        return "Local file (H-MEM sidecar not running)";
    }

    private List<Map<String, Object>> load() {
        if (!Files.exists(path)) return new ArrayList<>();
        return JSON.readValue(path.toFile(), new TypeReference<List<Map<String, Object>>>() { });
    }

    private void save(List<Map<String, Object>> items) {
        try {
            Files.createDirectories(path.getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), items);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not save memory to " + path, e);
        }
    }

    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }

    @Override
    public synchronized Map<String, Object> remember(String fingerprint, String decision, String title, String reason, String by) {
        List<Map<String, Object>> items = load();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", "mem-" + (items.size() + 1));
        item.put("fingerprint", fingerprint);
        item.put("created_at", now());
        item.put("content", Memory.text(fingerprint, decision, title, reason, by));
        item.put("history", new ArrayList<>(List.of(Map.of("action", "created", "actor", by, "timestamp", now()))));
        items.add(item);
        save(items);
        Map<String, Object> out = new LinkedHashMap<>(item);
        out.put("decision", decision);
        return out;
    }

    @Override
    public synchronized Map<String, Object> recall(String fingerprint, String title) {
        List<Map<String, Object>> items = load();
        for (int i = items.size() - 1; i >= 0; i--) {
            Map<String, Object> m = items.get(i);
            if (fingerprint.equals(m.get("fingerprint"))) {
                Map<String, Object> out = new LinkedHashMap<>(m);
                out.put("relevance", 1.0);
                out.put("strategy", "exact fingerprint");
                out.put("decision", Memory.decisionOf(m.get("content")));
                return out;
            }
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized void used(String memoryId) {
        List<Map<String, Object>> items = load();
        for (Map<String, Object> m : items) {
            if (memoryId.equals(m.get("id"))) {
                ((List<Map<String, Object>>) m.get("history")).add(Map.of("action", "recalled", "actor", "tally", "timestamp", now()));
            }
        }
        save(items);
    }

    @Override
    public synchronized List<Map<String, Object>> all() {
        List<Map<String, Object>> items = load();
        items.forEach(m -> m.put("decision", Memory.decisionOf(m.get("content"))));
        return items;
    }
}
