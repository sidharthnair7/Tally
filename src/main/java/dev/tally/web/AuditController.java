package dev.tally.web;

import dev.tally.audit.Explain;
import dev.tally.audit.Finding;
import dev.tally.audit.Investigation;
import dev.tally.audit.Sql;
import dev.tally.data.Backend;
import dev.tally.data.Env;
import dev.tally.memory.FileMemory;
import dev.tally.memory.HmemMemory;
import dev.tally.memory.Memory;
import dev.tally.memory.Scoped;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

/** The audit stream, the controller's decisions, evidence packs and the memo. */
@RestController
public class AuditController {

    private static final String COMPANY = "Brightwater Components Inc.";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path root = Path.of(System.getProperty("tally.root", System.getProperty("user.dir")));
    private final Env env = new Env(root);
    private final Backend db = env.openBackend(root);
    private final boolean perVisitor = "1".equals(env.get("TALLY_PER_VISITOR"));
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private final ReentrantLock auditLock = new ReentrantLock();
    /** Each workspace's last audit, for decisions, evidence packs and the memo. */
    private final Map<String, Map<String, Object>> last = new ConcurrentHashMap<>();
    private final Map<String, List<Map<String, Object>>> steps = new ConcurrentHashMap<>();

    /** Meterless H-MEM when its sidecar answers, a local file otherwise; checked on each request. */
    private Memory baseMemory() {
        HmemMemory hmem = new HmemMemory(env.get("TALLY_MEMORY_URL", "http://127.0.0.1:8787"));
        return hmem.ok() ? hmem : new FileMemory(root.resolve("data").resolve(".memory.json"));
    }

    private Scoped memoryFor(HttpServletRequest req, HttpServletResponse res) {
        String ws = "";
        if (perVisitor) {
            if (req.getCookies() != null) {
                for (Cookie c : req.getCookies()) {
                    if (c.getName().equals("tally_ws") && c.getValue().matches("[a-f0-9]{1,32}")) ws = c.getValue();
                }
            }
            if (ws.isEmpty() && res != null) {
                byte[] b = new byte[8];
                new SecureRandom().nextBytes(b);
                ws = HexFormat.of().formatHex(b);
                Cookie c = new Cookie("tally_ws", ws);
                c.setMaxAge(7 * 86400);
                c.setHttpOnly(true);
                c.setPath("/");
                res.addCookie(c);
            }
        }
        return new Scoped(baseMemory(), ws);
    }

    @GetMapping("/api/status")
    public Map<String, Object> status(HttpServletRequest req, HttpServletResponse res) {
        Explain.Model model = Explain.Model.from(env);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", COMPANY);
        out.put("backend", Map.of("mode", db.mode(), "label", db.label()));
        Map<String, Object> systems = new LinkedHashMap<>();
        Backend.SYSTEM_ORDER.forEach(s -> systems.put(s, Backend.SYSTEMS.get(s)));
        out.put("systems", systems);
        out.put("memory", memoryFor(req, res).kind());
        out.put("model", model == null ? null : model.label());
        return out;
    }

    @GetMapping(value = "/api/audit", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter audit(HttpServletRequest req) {
        Scoped memory = memoryFor(req, null);
        SseEmitter emitter = new SseEmitter(0L);
        pool.submit(() -> {
            auditLock.lock();
            try {
                db.log.clear();
                List<Map<String, Object>> trace = new ArrayList<>();
                Map<String, Object> result = Investigation.run(db, memory, Explain.Model.from(env), event -> {
                    if ("step".equals(event.get("type"))) trace.add(event);
                    send(emitter, event);
                });
                last.put(memory.workspace, result);
                steps.put(memory.workspace, trace);
                emitter.complete();
            } catch (Exception e) {
                send(emitter, Map.of("type", "error", "message", e.getClass().getSimpleName() + ": " + e.getMessage()));
                emitter.complete();
            } finally {
                auditLock.unlock();
            }
        });
        return emitter;
    }

    private static void send(SseEmitter emitter, Map<String, Object> event) {
        try {
            emitter.send(SseEmitter.event().data(JSON.writeValueAsString(event), MediaType.TEXT_PLAIN));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("The page closed the audit stream", e);
        }
    }

    public record Decision(String finding_id, String decision, String reason, String by) {
    }

    @SuppressWarnings("unchecked")
    private Finding finding(String workspace, String id) {
        Map<String, Object> result = last.get(workspace);
        if (result != null) {
            for (Finding f : (List<Finding>) result.get("findings")) {
                if (f.id.equals(id)) return f;
            }
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such finding in the last audit. Run the audit first.");
    }

    @PostMapping("/api/decision")
    public Map<String, Object> decide(@RequestBody Decision d, HttpServletRequest req) {
        if (!List.of("expected", "fraud").contains(d.decision())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "decision must be 'expected' or 'fraud'");
        }
        Scoped memory = memoryFor(req, null);
        Finding f = finding(memory.workspace, d.finding_id());
        String reason = d.reason() == null || d.reason().isBlank()
                ? (d.decision().equals("expected") ? "Expected for this vendor" : "Confirmed by the controller")
                : d.reason().strip().substring(0, Math.min(300, d.reason().strip().length())).replaceAll("\\.+$", "");
        String by = d.by() == null ? "Karen Liu, Controller" : d.by();
        Map<String, Object> saved = new LinkedHashMap<>(memory.remember(f.fingerprint(), d.decision(), f.title, reason, by));
        saved.put("kind", memory.kind());
        return saved;
    }

    @GetMapping("/api/memory")
    public Map<String, Object> memories(HttpServletRequest req) {
        Scoped memory = memoryFor(req, null);
        return Map.of("kind", memory.kind(), "items", memory.all());
    }

    @SuppressWarnings("unchecked")
    @GetMapping(value = "/api/evidence/{id}", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> evidencePack(@PathVariable String id, HttpServletRequest req) {
        String ws = memoryFor(req, null).workspace;
        Finding f = finding(ws, id);
        Map<String, Object> result = last.get(ws);
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        Map<String, Map<String, Object>> evidence = (Map<String, Map<String, Object>>) result.get("evidence");
        List<String> period = (List<String>) summary.get("period");
        StringBuilder md = new StringBuilder("# Evidence pack: " + f.title + "\n\n");
        md.append("- Company: ").append(COMPANY).append(", books ").append(period.get(0)).append(" to ").append(period.get(1)).append('\n')
                .append("- Check: ").append(f.detector).append(" · ").append(f.check).append('\n')
                .append("- Verdict: **").append(f.verdict).append("**\n")
                .append("- Amount at risk: ").append(Sql.usd(f.amount)).append('\n')
                .append("- Systems: ").append(String.join(", ", f.systems.stream().map(s -> Backend.SYSTEMS.get(s).get("name")).toList())).append('\n')
                .append("- Read through: ").append(summary.get("backend")).append("\n\n## Why\n\n");
        for (Map<String, Object> s : f.explanation) {
            md.append("- ").append(s.get("text")).append(" (").append(String.join(", ", (List<String>) s.get("refs"))).append(")\n");
        }
        if (!f.reasons.isEmpty()) {
            md.append("\n## Notes\n\n");
            f.reasons.forEach(r -> md.append("- ").append(r).append('\n'));
        }
        md.append("\n## The rows\n\n");
        for (String ref : f.refs) {
            Map<String, Object> row = evidence.get(ref);
            if (row == null) continue;
            md.append("### ").append(ref).append("\n\n| field | value |\n|---|---|\n");
            row.forEach((k, v) -> md.append("| ").append(k).append(" | ").append(String.valueOf(v).replace("|", "/")).append(" |\n"));
            md.append('\n');
        }
        List<Map<String, Object>> queries = new ArrayList<>();
        steps.getOrDefault(ws, List.of()).stream().filter(s -> f.detector.equals(s.get("id")))
                .forEach(s -> queries.addAll((List<Map<String, Object>>) s.get("queries")));
        if (!queries.isEmpty()) {
            md.append("## The SQL that found it\n\n");
            for (Map<String, Object> q : queries) {
                md.append("-- ").append(q.get("purpose")).append(" (").append(q.get("rows")).append(" rows, ").append(q.get("ms"))
                        .append(" ms)\n```sql\n").append(q.get("sql")).append("\n```\n\n");
            }
        }
        String name = "tally-evidence-" + id.replace(':', '-') + ".md";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"").body(md.toString());
    }

    @SuppressWarnings("unchecked")
    @GetMapping(value = "/api/memo", produces = "text/plain;charset=UTF-8")
    public String memo(HttpServletRequest req) {
        Map<String, Object> result = last.get(memoryFor(req, null).workspace);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Run the audit first.");
        Map<String, Object> s = (Map<String, Object>) result.get("summary");
        List<String> period = (List<String>) s.get("period");
        List<Finding> findings = (List<Finding>) result.get("findings");
        List<Finding> open = findings.stream().filter(Finding::open).toList();
        StringBuilder out = new StringBuilder();
        out.append("To: Tom Becker, Chief Financial Officer\nFrom: Tally, for Karen Liu, Controller\n")
                .append("Date: ").append(LocalDate.now()).append('\n')
                .append("Re: Q3 payables review, ").append(period.get(0)).append(" to ").append(period.get(1)).append("\n\n")
                .append(String.format("Tally checked all %,d records across four systems (ERP, vendor master, HR, bank feed). ", (long) s.get("records")))
                .append(open.size()).append(" findings need action, with ").append(Sql.usd((double) s.get("at_risk"))).append(" at risk.\n\n");
        int n = 1;
        for (Finding f : open) {
            out.append(n++).append(". ").append(f.title).append(" (").append(f.verdict.toLowerCase()).append(", ").append(Sql.usd(f.amount)).append(")\n");
            f.explanation.forEach(x -> out.append("   ").append(x.get("text")).append('\n'));
            f.reasons.stream().filter(r -> r.contains("behind")).forEach(r -> out.append("   Note: ").append(r).append('\n'));
            out.append('\n');
        }
        List<Finding> aside = findings.stream().filter(f -> !f.open()).toList();
        if (!aside.isEmpty()) {
            out.append("Set aside, with the reason:\n");
            aside.forEach(f -> out.append("- ").append(f.title).append(": ").append(f.reasons.isEmpty() ? "" : f.reasons.getLast()).append('\n'));
        }
        return out.append("\nEvery finding above cites the rows behind it; the evidence pack for each is in Tally.\n").toString();
    }
}
