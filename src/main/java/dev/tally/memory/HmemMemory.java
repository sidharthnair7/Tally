package dev.tally.memory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** The controller's decisions kept in Meterless H-MEM, through the small sidecar in memory/server.ts. */
public class HmemMemory implements Memory {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String url;

    public HmemMemory(String url) {
        this.url = url.replaceAll("/+$", "");
    }

    @Override
    public String kind() {
        return "Meterless H-MEM";
    }

    private <T> T call(String path, Object body, TypeReference<T> type) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json");
            req = body == null ? req.GET() : req.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) throw new IllegalStateException("H-MEM sidecar: " + res.body());
            return JSON.readValue(res.body(), type);
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException("H-MEM sidecar unreachable at " + url, e);
        }
    }

    public boolean ok() {
        try {
            return Boolean.TRUE.equals(call("/health", null, new TypeReference<Map<String, Object>>() { }).get("ok"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Map<String, Object> remember(String fingerprint, String decision, String title, String reason, String by) {
        Map<String, Object> out = new LinkedHashMap<>(call("/remember", Map.of("fingerprint", fingerprint, "actor", by,
                "text", Memory.text(fingerprint, decision, title, reason, by)), new TypeReference<Map<String, Object>>() { }));
        out.put("decision", decision);
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> recall(String fingerprint, String title) {
        Map<String, Object> out = call("/recall", Map.of("fingerprint", fingerprint, "query", "[tally:" + fingerprint + "] " + title),
                new TypeReference<Map<String, Object>>() { });
        Map<String, Object> hit = (Map<String, Object>) out.get("hit");
        if (hit == null) return null;
        Map<String, Object> result = new LinkedHashMap<>(hit);
        result.put("decision", Memory.decisionOf(hit.get("content")));
        return result;
    }

    @Override
    public void used(String memoryId) {
        call("/feedback", Map.of("id", memoryId, "signal", "helpful"), new TypeReference<Map<String, Object>>() { });
    }

    @Override
    public List<Map<String, Object>> all() {
        List<Map<String, Object>> items = call("/memories", null, new TypeReference<List<Map<String, Object>>>() { });
        items.forEach(m -> m.put("decision", Memory.decisionOf(m.get("content"))));
        return items;
    }
}
