"use strict";
const $ = (s, el = document) => el.querySelector(s);
const usd = n => Number(n).toLocaleString("en-US", { style: "currency", currency: "USD" });
const esc = s => String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const sleep = ms => new Promise(r => setTimeout(r, ms));
const cssId = id => id.replace(/[^a-zA-Z0-9_-]/g, "_");
const SYS = { erp: "ERP", vendors: "Vendor master", hr: "HR", bank: "Bank feed" };
const stripTag = s => String(s || "").replace(/^\[tally:[^\]]+\]\s*/, "");

let STATUS = null, LAST = null, queue = [], pumping = false, source = null;

async function loadStatus() {
  STATUS = await (await fetch("/api/status")).json();
  $("#b-data").textContent = STATUS.backend.mode === "zetaris" ? "Zetaris · live" : "H2 · offline";
  $("#b-data").parentElement.title = STATUS.backend.label;
  $("#b-mem").textContent = STATUS.memory;
  $("#b-model").textContent = STATUS.model || "verifier only";
  renderSources({});
}

function renderSources(counts) {
  $("#sources").innerHTML = Object.entries(STATUS.systems).map(([k, s]) => {
    const tables = Object.entries(counts).filter(([src]) => src.startsWith(k + "."));
    const txt = tables.map(([src, n]) => `${src.split(".")[1]} ${Number(n).toLocaleString()}`).join(" · ");
    return `<div class="src ${tables.length ? "live" : ""}"><div class="s-name">${esc(s.name)}<span class="sys">${esc(k)}</span></div>
      <div class="s-about">${esc(s.about)}</div><div class="s-count">${esc(txt)}</div></div>`;
  }).join("");
}

function startAudit() {
  if (source) source.close();
  $("#run").disabled = true;
  $("#run").textContent = "Auditing…";
  $("#steps").innerHTML = "";
  ["#empty", "#headline", "#setaside", "#toast"].forEach(s => $(s).classList.add("hidden"));
  $("#findings").innerHTML = "";
  $("#links").innerHTML = "";
  $("#drawer").classList.add("hidden");
  queue = [];
  source = new EventSource("/api/audit");
  source.onmessage = ev => {
    const e = JSON.parse(ev.data);
    queue.push(e);
    if (e.type !== "step") { source.close(); source = null; }
    pump();
  };
  source.onerror = () => {
    if (!source) return;
    source.close();
    source = null;
    queue.push({ type: "error", message: "Lost the connection to the server." });
    pump();
  };
}

async function pump() {
  if (pumping) return;
  pumping = true;
  while (queue.length) {
    const e = queue.shift();
    if (e.type === "step") { addStep(e); await sleep(/^D\d/.test(e.id) ? 420 : 300); }
    else if (e.type === "result") { await sleep(150); showResult(e); }
    else showError(e.message);
  }
  pumping = false;
}

function addStep(e) {
  if (e.counts) renderSources(e.counts);
  const li = document.createElement("li");
  li.className = "step" + (e.found ? " hit" : "");
  const qs = e.queries.map(q => `<div class="q"><div class="qp">${esc(q.purpose)}</div>
    <div class="qs">${q.systems.map(s => `<span class="sys">${esc(SYS[s] || s)}</span>`).join("")}
    <span class="ms">${q.rows} rows · ${q.ms} ms</span></div><pre class="sql">${esc(q.sql)}</pre></div>`).join("");
  const title = /^D\d/.test(e.id) ? `${e.id} · ${e.title}` : e.title;
  li.innerHTML = `<div class="st"><span>${esc(title)}</span><span class="ms">${e.queries.length ? `${e.queries.length} SQL · ${e.ms} ms` : ""}</span></div>
    <div class="sd">${esc(e.detail)}${e.found ? ` <b style="color:var(--red)">→ ${e.found} finding${e.found > 1 ? "s" : ""}</b>` : ""}</div>
    ${e.queries.length ? `<details><summary>Show the SQL</summary>${qs}</details>` : ""}`;
  $("#steps").appendChild(li);
  li.scrollIntoView({ block: "nearest" });
}

function showError(msg) {
  $("#run").disabled = false;
  $("#run").textContent = "Run audit";
  toast(`The audit stopped: ${msg}`);
}

function countUp(el, to) {
  const t0 = performance.now(), d = 900;
  const tick = t => {
    const p = Math.min(1, (t - t0) / d);
    el.textContent = usd(to * (1 - Math.pow(1 - p, 3)));
    if (p < 1) requestAnimationFrame(tick);
  };
  requestAnimationFrame(tick);
}

function showResult(r) {
  LAST = r;
  const s = r.summary;
  $("#run").disabled = false;
  $("#run").textContent = "Run audit again";
  $("#headline").classList.remove("hidden");
  countUp($("#h-risk"), s.at_risk);
  $("#h-stats").innerHTML = [[s.open, "open findings"], [s.escalated, "escalated"], [s.remembered, "remembered"],
    [s.cleared, "cleared by the books"]].map(([n, l]) => `<span class="stat"><b>${n}</b>${l}</span>`).join("");
  $("#h-sub").textContent = `Checked all ${s.records.toLocaleString()} records in 4 systems, ${s.period[0]} to ${s.period[1]}, ` +
    `with ${s.queries} queries in ${s.seconds} s. ${s.backend}.`;
  renderLinks(r);
  const open = r.findings.filter(f => f.verdict === "ESCALATE" || f.verdict === "FLAG");
  const aside = r.findings.filter(f => f.verdict === "REMEMBERED" || f.verdict === "CLEAR");
  $("#findings").innerHTML = open.map(card).join("");
  if (aside.length) {
    const rem = aside.filter(f => f.verdict === "REMEMBERED").length;
    $("#setaside").classList.remove("hidden");
    $("#setaside").open = rem > 0;
    $("#setaside-sum").textContent = `Set aside: ${aside.length} · ${rem} remembered from your decisions · ` +
      `${aside.length - rem} cleared by evidence in the books`;
    $("#setaside-list").innerHTML = aside.map(card).join("");
  }
  loadMemory();
}

function refChip(ref) {
  const i = ref.indexOf(":"), src = ref.slice(0, i), key = ref.slice(i + 1);
  return `<button class="ref" data-ref="${esc(ref)}" title="${esc(src)}">${esc(SYS[src.split(".")[0]] || src)} · ${esc(key)}</button>`;
}

function card(f, i) {
  const why = f.explanation.map(s => `<li>${esc(s.text)} ${s.refs.map(refChip).join("")}</li>`).join("");
  const mem = f.memory ? `<div class="reason mem">Memory <b>${esc(f.memory.id)}</b>: ${esc(stripTag(f.memory.content))}` +
    (f.memory.relevance != null ? ` <span class="muted">(recalled by ${esc(f.memory.strategy || "H-MEM")}, relevance ${Number(f.memory.relevance).toFixed(2)})</span>` : "") + `</div>` : "";
  const reasons = f.reasons.map(r => `<div class="reason">${esc(r)}</div>`).join("");
  const by = f.explained_by && f.explained_by !== "verifier"
    ? `Worded by ${esc(f.explained_by)} · <b>every sentence checked against its rows</b>`
    : "Worded by the verifier, straight from the rows";
  const open = f.verdict === "ESCALATE" || f.verdict === "FLAG";
  return `<article class="card v-${f.verdict}" id="card-${cssId(f.id)}" style="animation-delay:${i * 60}ms">
    <div class="row1"><span class="chip ${f.verdict}">${f.verdict}</span><span class="check">${esc(f.detector)} · ${esc(f.check)}</span>
      ${f.systems.map(s => `<span class="sys">${esc(SYS[s])}</span>`).join("")}<span class="amt">${usd(f.amount)}</span></div>
    <h3>${esc(f.title)}</h3><ul class="why">${why}</ul>
    ${mem || reasons ? `<div class="reasons">${mem}${reasons}</div>` : ""}
    <div class="foot"><span class="by">${by}</span>
      ${open ? `<button class="ghost btn-fraud" data-act="fraud" data-id="${esc(f.id)}">Confirm fraud</button>
      <button class="ghost btn-ok" data-act="expected" data-id="${esc(f.id)}">Expected…</button>` : ""}
      <a class="ghost" href="/api/evidence/${encodeURIComponent(f.id)}">Evidence pack</a></div>
    <div class="decide hidden" data-for="${esc(f.id)}"><input placeholder="Why is this expected? e.g. split shipment, two deliveries on one PO">
      <button class="primary" data-act="save" data-id="${esc(f.id)}">Remember</button></div>
  </article>`;
}

function edgeLabel(f, pid) {
  const x = f.facts, parts = [];
  if (f.detector === "D2") parts.push(x.matched_on === "bank account" ? "paid into their bank account" : "shares their address");
  if (x.created_by === pid) parts.push("set up the vendor");
  if (f.detector === "D5" && x.requested_by === pid) parts.push("requested the payment");
  if (["D1", "D3"].includes(f.detector)) parts.push("requested the invoices");
  if (f.detector === "D6") parts.push("approved their own invoice");
  return parts.join(", ");
}

function renderLinks(r) {
  $("#links").innerHTML = r.links.map(l => {
    const fs = l.findings.map(id => r.findings.find(f => f.id === id)).filter(Boolean);
    const total = fs.reduce((a, f) => a + f.amount, 0);
    const lead = r.leads.find(x => x.person === l.person);
    const n = fs.length, h = 24 + n * 84, py = h / 2;
    const nodes = fs.map((f, k) => {
      const y = 14 + k * 84;
      return `<path d="M232 ${py} C 330 ${py}, 330 ${y + 32}, 400 ${y + 32}" stroke="#ff6b6b" stroke-opacity=".55" stroke-width="2" fill="none"/>
        <rect x="400" y="${y}" width="364" height="66" rx="10" fill="#1a1d29" stroke="#4a2630"/>
        <text x="416" y="${y + 21}" fill="#e9ebf1" font-size="13.5" font-weight="600">${esc(f.facts.vendor || f.title)}</text>
        <text x="416" y="${y + 39}" fill="#8a92a6" font-size="12">${esc(f.detector)} · ${esc(f.check)} · ${usd(f.amount)}</text>
        <text x="416" y="${y + 56}" fill="#ff9b9b" font-size="12">${esc(l.name.split(" ")[0])}: ${esc(edgeLabel(f, l.person))}</text>`;
    }).join("");
    let leadTxt = "";
    if (lead) {
      const invs = lead.invoices_requested.reduce((a, x) => a + Number(x.invoices), 0);
      const own = lead.invoices_requested.every(x => lead.vendors_created.some(v => v.vendor_id === x.vendor_id));
      leadTxt = ` Following the lead: ${esc(lead.name)} set up ${lead.vendors_created.length} vendor(s) since ${esc(lead.since)} and ` +
        `requested ${invs} invoice(s) this quarter${own && invs ? ", every one of them to a vendor they set up" : ""}.`;
    }
    return `<div class="link"><h4>Connected · one person behind ${n} findings</h4>
      <p><b>${esc(l.name)}</b>, ${esc(l.title)}, is behind ${usd(total)} across ${n} findings.${leadTxt}</p>
      <svg viewBox="0 0 780 ${h}" role="img" aria-label="${esc(l.name)} connected to ${n} findings">
        <rect x="16" y="${py - 30}" width="216" height="60" rx="12" fill="#2a1219" stroke="#ff6b6b"/>
        <text x="32" y="${py - 6}" fill="#fff" font-size="15" font-weight="700">${esc(l.name)}</text>
        <text x="32" y="${py + 14}" fill="#ffb3b3" font-size="12">${esc(l.title)}</text>${nodes}</svg></div>`;
  }).join("");
}

async function decide(id, decision, reason) {
  const res = await fetch("/api/decision", { method: "POST", headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ finding_id: id, decision, reason }) });
  const out = await res.json();
  if (!res.ok) return toast(out.detail || "Could not save the decision.");
  const c = document.getElementById("card-" + cssId(id));
  c.querySelectorAll(".foot button").forEach(b => b.remove());
  c.querySelector(".decide").classList.add("hidden");
  c.querySelector(".foot").insertAdjacentHTML("beforeend", `<span class="saved">Saved as ${esc(out.id)} in ${esc(out.kind)}</span>`);
  toast(decision === "expected"
    ? `Remembered as expected (${out.id}). Run the audit again and Tally will set this pattern aside, citing your decision.`
    : `Confirmed as fraud (${out.id}). Tally will keep it in front of you if the pattern comes back.`, true);
  loadMemory();
}

function toast(msg, rerun) {
  const t = $("#toast");
  t.innerHTML = `<span>${esc(msg)}</span>${rerun ? `<button class="primary" id="rerun">Run audit again</button>` : ""}`;
  t.classList.remove("hidden");
  const b = $("#rerun");
  if (b) b.onclick = startAudit;
}

function openRef(ref) {
  const row = LAST && LAST.evidence[ref];
  if (!row) return;
  const i = ref.indexOf(":"), src = ref.slice(0, i);
  $("#d-src").textContent = `${SYS[src.split(".")[0]]} · ${src.split(".")[1]} table · the exact row behind this sentence`;
  $("#d-title").textContent = ref.slice(i + 1);
  $("#d-table").innerHTML = Object.entries(row).map(([k, v]) => `<tr><td>${esc(k)}</td><td>${esc(v)}</td></tr>`).join("");
  $("#drawer").classList.remove("hidden");
}

async function loadMemory() {
  const m = await (await fetch("/api/memory")).json();
  $("#memory").classList.toggle("hidden", !m.items.length);
  $("#mem-kind").textContent = "· " + m.kind;
  $("#mem-list").innerHTML = m.items.slice().reverse().map(x => `<div class="mem"><span class="mid">${esc(x.id)}</span>
    <span class="chip ${x.decision === "expected" ? "REMEMBERED" : "ESCALATE"}">${x.decision === "expected" ? "EXPECTED" : "FRAUD"}</span>
    ${esc(stripTag(x.content))}${x.history && x.history.length ? `<div class="muted" style="font-size:12px;margin-top:2px">Trust ledger: ${x.history.map(h => esc(h.action) + " by " + esc(h.actor)).join(" → ")}</div>` : ""}</div>`).join("");
}

document.addEventListener("click", async ev => {
  const t = ev.target.closest("[data-ref],[data-act]");
  if (!t) return;
  if (t.dataset.ref) return openRef(t.dataset.ref);
  const id = t.dataset.id, act = t.dataset.act;
  if (act === "expected") {
    const box = document.querySelector(`.decide[data-for="${CSS.escape(id)}"]`);
    box.classList.toggle("hidden");
    box.querySelector("input").focus();
  } else if (act === "save") {
    await decide(id, "expected", t.closest(".decide").querySelector("input").value);
  } else if (act === "fraud") {
    await decide(id, "fraud", "");
  }
});
document.addEventListener("keydown", e => {
  if (e.key === "Escape") $("#drawer").classList.add("hidden");
  if (e.key === "Enter" && e.target.matches(".decide input")) e.target.closest(".decide").querySelector("[data-act=save]").click();
});
$("#d-close").onclick = () => $("#drawer").classList.add("hidden");
$("#run").onclick = startAudit;
loadStatus().then(loadMemory);
