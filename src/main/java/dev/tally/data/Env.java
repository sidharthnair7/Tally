package dev.tally.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Settings from the environment, then from .env.local (gitignored). No secrets live in the code. */
public class Env {

    private final Map<String, String> values = new HashMap<>();

    public Env(Path root) {
        Path file = root.resolve(".env.local");
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file)) {
                    line = line.strip();
                    int eq = line.indexOf('=');
                    if (!line.isEmpty() && !line.startsWith("#") && eq > 0) {
                        values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip().replaceAll("^[\"']|[\"']$", ""));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Could not read " + file, e);
            }
        }
        values.putAll(System.getenv());
    }

    public String get(String key, String fallback) {
        String v = values.get(key);
        return v == null || v.isBlank() ? fallback : v;
    }

    public String get(String key) {
        return get(key, null);
    }

    public String require(String key) {
        String v = get(key);
        if (v == null) throw new IllegalStateException("Set " + key + " in .env.local");
        return v;
    }

    /** Live Zetaris when TALLY_BACKEND=zetaris (or a Zetaris key is set), otherwise offline. */
    public Backend openBackend(Path root) {
        String want = get("TALLY_BACKEND", get("ZETARIS_API_KEY") != null || get("ZETARIS_JDBC_JAR") != null ? "zetaris" : "offline");
        return want.equals("zetaris") ? new ZetarisBackend(this) : new H2Backend(root.resolve("data").resolve("sources"));
    }
}
