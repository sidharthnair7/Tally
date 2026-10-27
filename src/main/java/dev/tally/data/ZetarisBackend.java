package dev.tally.data;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Live mode: Zetaris Lightning over its REST API. Each system is its own datasource (ZETARIS_DS_ERP and so on);
 * Zetaris pushes each query down to the systems and joins the results, so nothing is copied out first.
 */
public class ZetarisBackend extends Backend {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String base;
    private final String org;
    private final String key;
    private final Map<String, String> datasource = new LinkedHashMap<>();

    public ZetarisBackend(Env env) {
        base = env.get("ZETARIS_REST_URL", "http://localhost:8888/api/v1.0").replaceAll("/+$", "");
        org = env.get("ZETARIS_ORG_ID", "1");
        key = env.require("ZETARIS_API_KEY");
        for (String s : SYSTEM_ORDER) {
            datasource.put(s, env.get("ZETARIS_DS_" + s.toUpperCase(), "tally_" + s));
        }
    }

    @Override
    public String mode() {
        return "zetaris";
    }

    @Override
    public String label() {
        return "Live: four Zetaris datasources, queried in place";
    }

    @Override
    protected String where(String system, String table) {
        return datasource.get(system) + "." + table;
    }

    @Override
    protected List<Map<String, Object>> run(String sql) {
        String body = JSON.writeValueAsString(Map.of("queryId", UUID.randomUUID().toString(), "sql", sql,
                "source", "SqlEditor", "limit", 100000));
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/sql-editor/sqls/run"))
                .timeout(Duration.ofMinutes(5))
                .header("Authorization", "Bearer " + key).header("X-Org-ID", org)
                .header("X-Request-ID", UUID.randomUUID().toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        try {
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                String text = res.body();
                throw new IllegalStateException("Zetaris HTTP " + res.statusCode() + ": " + text.substring(0, Math.min(400, text.length())));
            }
            return rows(JSON.readTree(res.body()));
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException("Cannot reach Zetaris at " + base + ": " + e.getMessage(), e);
        }
    }

    /** Zetaris returns rows either as objects or as arrays with a separate list of column names. */
    static List<Map<String, Object>> rows(JsonNode out) {
        JsonNode data = out.isObject() ? out.path("data") : out;
        if (data.isObject()) {
            data = data.has("rows") ? data.get("rows") : data.has("records") ? data.get("records") : data.path("data");
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!data.isArray() || data.isEmpty()) return rows;
        List<String> cols = null;
        if (!data.get(0).isObject()) {
            for (String k : List.of("columns", "columnNames", "headers", "schema", "fields")) {
                if (out.has(k)) {
                    cols = new ArrayList<>();
                    for (JsonNode c : out.get(k)) cols.add(c.isObject() ? c.path("name").asString() : c.asString());
                    break;
                }
            }
            if (cols == null) throw new IllegalStateException("Zetaris returned rows without column names");
        }
        for (JsonNode r : data) {
            Map<String, Object> row = new LinkedHashMap<>();
            if (r.isObject()) {
                for (Map.Entry<String, JsonNode> e : r.properties()) row.put(shortName(e.getKey()), value(e.getValue()));
            } else {
                for (int i = 0; i < cols.size(); i++) row.put(shortName(cols.get(i)), value(r.get(i)));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String shortName(String col) {
        return col.substring(col.lastIndexOf('.') + 1).toLowerCase();
    }

    private static Object value(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isIntegralNumber()) return v.asLong();
        if (v.isNumber()) return v.asDouble();
        if (v.isBoolean()) return v.asBoolean();
        String s = v.asString();
        return s.matches("\\d{4}-\\d{2}-\\d{2}[ T]00:00:00(\\.0+)?") ? s.substring(0, 10) : s;
    }
}
