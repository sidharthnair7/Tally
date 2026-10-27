package dev.tally.data;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registers the four systems as Zetaris datasources by running zetaris/register.sql over JDBC, one statement at a
 * time, with ${PG_HOST}, ${PG_USER} and ${PG_PASSWORD} filled in from .env.local. Existing datasources are skipped.
 *
 *     ./mvnw -q exec:java -Dexec.mainClass=dev.tally.data.RegisterZetaris   (or run main from the IDE)
 */
public final class RegisterZetaris {

    public static void main(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("user.dir"));
        Env env = new Env(root);
        String sql = Files.readString(root.resolve("zetaris").resolve("register.sql"));
        sql = sql.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + b + "\n");
        Matcher m = Pattern.compile("\\$\\{(\\w+)}").matcher(sql);
        StringBuilder filled = new StringBuilder();
        while (m.find()) m.appendReplacement(filled, Matcher.quoteReplacement(env.get(m.group(1), m.group(0))));
        m.appendTail(filled);
        ZetarisBackend zetaris = new ZetarisBackend(env);
        for (String stmt : filled.toString().split(";")) {
            stmt = stmt.strip();
            if (stmt.isEmpty()) continue;
            String head = stmt.lines().findFirst().orElse("");
            try {
                System.out.println("ok   " + head + "  " + zetaris.execute(stmt));
            } catch (RuntimeException e) {
                String msg = String.valueOf(e.getMessage());
                if (msg.toLowerCase().contains("already exist")) System.out.println("skip " + head + " (already exists)");
                else throw e;
            }
        }
    }
}
