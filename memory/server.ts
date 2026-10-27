// Tally's memory: the controller's decisions, kept in Meterless H-MEM.
// A small HTTP sidecar that the Python app calls. Each route maps to a named H-MEM service:
//   POST /remember   MemoryStoreService      store a decision ("expected" or "confirmed fraud")
//   POST /recall     MemoryRetrievalService  hybrid search for a past decision on this pattern
//   POST /feedback   feedback delta          mark a memory helpful each time it sets a finding aside
//   GET  /memories   TrustLedgerService      every decision with its append-only history
//
//   npm install && npm run setup   (setup vendors the H-MEM reference engine into vendor/hmem)
//   npm start                      (listens on 127.0.0.1:8787)
import fs from "node:fs";
import http from "node:http";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const entry = path.join(here, "vendor", "hmem", "src", "index.ts");
if (!fs.existsSync(entry)) {
  console.error("H-MEM engine not found. Run the setup step first:\n\n  npm run setup\n");
  process.exit(1);
}
const { HMEM } = await import(pathToFileURL(entry).href);
const stateDir = path.join(here, ".state");
fs.mkdirSync(stateDir, { recursive: true });
const hmem = new HMEM({ persistDir: stateDir });

// Tally's own list of which H-MEM memories are controller decisions, so the page can show them all.
type Entry = { id: string; fingerprint: string; content: string; layer?: string; created_at: string };
const indexFile = path.join(stateDir, "tally-decisions.json");
const index: Entry[] = fs.existsSync(indexFile) ? JSON.parse(fs.readFileSync(indexFile, "utf-8")) : [];
const saveIndex = () => fs.writeFileSync(indexFile, JSON.stringify(index, null, 2));

const history = (id: string) =>
  (hmem.ledger.history(id) || []).map((e: any) => ({
    action: e.action, actor: e.actor, timestamp: new Date(e.timestamp).toISOString(),
    confidence: e.newState?.confidence,
  }));

const routes: Record<string, (body: any) => unknown> = {
  "GET /health": () => ({ ok: true, engine: "Meterless H-MEM", decisions: index.length }),
  "POST /remember": ({ fingerprint, text, actor }) => {
    const record = hmem.add({ content: text, type: "general", source: `tally:${actor || "controller"}` });
    index.push({ id: record.id, fingerprint, content: record.content, layer: record.layer,
                 created_at: new Date().toISOString() });
    saveIndex();
    return { id: record.id, layer: record.layer, content: record.content };
  },
  "POST /recall": ({ fingerprint, query }) => {
    const result = hmem.query(query, { threshold: 0.05 });
    const tag = `[tally:${fingerprint}]`;
    const hit = result.memories.find((r: any) => String(r.memory.content).includes(tag));
    if (!hit) return { hit: null, candidates: result.memories.length, strategy: result.trace?.strategy };
    return { hit: { id: hit.memory.id, content: hit.memory.content, layer: hit.memory.layer,
                    relevance: hit.relevance, strategy: result.trace?.strategy } };
  },
  "POST /feedback": ({ id, signal }) => {
    hmem.feedback(id, signal || "helpful");
    return { ok: true, history: history(id) };
  },
  "GET /memories": () => index.map((m) => ({ ...m, history: history(m.id) })),
};

http.createServer((req, res) => {
  let raw = "";
  req.on("data", (c) => (raw += c));
  req.on("end", () => {
    const route = routes[`${req.method} ${(req.url || "").split("?")[0]}`];
    try {
      if (!route) throw Object.assign(new Error("not found"), { status: 404 });
      const out = route(raw ? JSON.parse(raw) : {});
      res.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify(out));
    } catch (err: any) {
      res.writeHead(err.status || 500, { "Content-Type": "application/json" }).end(JSON.stringify({ error: String(err.message || err) }));
    }
  });
}).listen(Number(process.env.PORT || 8787), "127.0.0.1", () =>
  console.log(`Tally memory (Meterless H-MEM) on http://127.0.0.1:${process.env.PORT || 8787}, ${index.length} decisions`));
