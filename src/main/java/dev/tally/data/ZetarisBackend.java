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
    private final String key;  // REST only
    private final Map<String, String> datasource = new LinkedHashMap<>();

    /** Set when ZETARIS_JDBC_JAR points at the driver jar from the Zetaris bundle; otherwise the REST API is used. */
    private java.sql.Connection jdbc;

    public ZetarisBackend(Env env) {
        base = env.get("ZETARIS_REST_URL", "http://localhost:8888/api/v1.0").replaceAll("/+$", "");
        org = env.get("ZETARIS_ORG_ID", "1");
        for (String s : SYSTEM_ORDER) {
            datasource.put(s, env.get("ZETARIS_DS_" + s.toUpperCase(), "tally_" + s));
        }
        String jar = env.get("ZETARIS_JDBC_JAR");
        if (jar != null) {
            key = null;
            jdbc = connect(jar, env.get("ZETARIS_JDBC_URL", "jdbc:zetaris:lightning@localhost:10000"),
                    env.require("ZETARIS_USERNAME"), env.require("ZETARIS_PASSWORD"));
        } else {
            key = env.require("ZETARIS_API_KEY");
        }
    }

    /** Loads the Lightning JDBC driver straight from the bundle's jar, so the build needs no Zetaris artifact. */
    private static java.sql.Connection connect(String jar, String url, String user, String password) {
        try {
            var loader = new java.net.URLClassLoader(new java.net.URL[]{java.nio.file.Path.of(jar).toUri().toURL()},
                    ZetarisBackend.class.getClassLoader());
            var driver = (java.sql.Driver) Class.forName("com.zetaris.lightning.jdbc.LightningDriver", true, loader)
                    .getDeclaredConstructor().newInstance();
            var props = new java.util.Properties();
            props.setProperty("user", user);
            props.setProperty("password", password);
            java.sql.Connection c = driver.connect(url, props);
            if (c == null) throw new IllegalStateException("The Zetaris driver did not accept " + url);
            return c;
        } catch (Exception e) {
            throw new IllegalStateException("Could not connect to Zetaris over JDBC: " + e.getMessage(), e);
        }
    }

    /** Runs any statement (DDL included) and returns its rows, if it has any. Used to register the datasources. */
    public String execute(String sql) {
        if (jdbc == null) return String.valueOf(run(sql));
        try (var st = jdbc.createStatement()) {
            boolean hasRows = st.execute(sql);
            if (!hasRows) return "";
            try (var rs = st.getResultSet()) {
                var md = rs.getMetaData();
                List<String> rows = new ArrayList<>();
                while (rs.next()) {
                    List<String> cells = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) cells.add(md.getColumnLabel(i) + "=" + rs.getObject(i));
                    rows.add(String.join(", ", cells));
                }
                return String.join(" | ", rows);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> runJdbc(String sql) {
        try (var st = jdbc.createStatement(); var rs = st.executeQuery(sql)) {
            var md = rs.getMetaData();
            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) row.put(shortName(md.getColumnLabel(i)), plain(rs.getObject(i)));
                rows.add(row);
            }
            return rows;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Zetaris: " + e.getMessage() + "\n" + sql, e);
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
        if (jdbc != null) return runJdbc(sql);
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
