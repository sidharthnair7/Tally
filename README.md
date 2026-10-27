# Tally

**Tally finds the money that shouldn't have left, shows you the rows that prove it, and remembers what you cleared last time.**

Open Agent Hackathon 2026 · Track 02: The Agent That Can Explain Why

Demo video: `Tally-demo.mp4` on the submission page

## The problem

A controller at a 50 to 300 person company signs off on thousands of payments a quarter. The evidence for a
duplicate payment, a fake vendor or a split invoice is never in one place: it is spread across the ERP, the vendor
master, the HR system and the bank feed. Auditors sample. Most rules-based tools look at one system at a time, and
they raise the same false alarm every month because they forget what the controller already explained.

## What Tally does

1. **Reads the books where they live.** Four separate systems, queried in place through Zetaris. Nothing is copied out first.
2. **Checks every transaction, not a sample**, against the company's own approval policy (read from the ERP, not hard-coded).
3. **Follows the people.** When a flagged vendor was set up by an employee, Tally pulls everything else that person touched, and connects findings that share a person.
4. **Explains why, with receipts.** Every sentence cites the exact rows behind it. Click a citation and the row opens.
5. **Remembers the controller's decisions** in Meterless H-MEM. Mark a finding "expected" once, with a reason, and next run Tally sets that pattern aside and says why, citing the memory.
6. **Never lets a model decide.** Verdicts come from the checks, the controller's decisions and the connections. A model (NVIDIA Nemotron, optional) can word the explanation, and each sentence must pass an evidence check or the verifier's own sentence is shown instead.

## Track 02, line by line

| The track asks for | Tally |
|---|---|
| Investigate complex questions | Six checks, each one SQL that joins systems plus a plain rule; then a follow-up investigation of the people involved |
| Across multiple data sources | ERP, vendor master, HR and bank feed, four separate datasources read in place through Zetaris |
| Connect findings | Findings that share a person are linked and escalated (one AP specialist behind two vendors) |
| Evidence-backed answers | Every sentence cites `system.table:key` rows; a downloadable evidence pack per finding holds the rows and the SQL |

## How it works

```
ERP (invoices, payments, policy) ─┐
Vendor master                     ├─ Zetaris: one SQL door, each system its own datasource, queried in place
HR (employees)                    │
Bank feed (transactions)          ┘
        │
        ▼
 read → policy → six checks → recall decisions (Meterless H-MEM) → follow the people → connect → explain
                     │                                                                     │
          SQL finds candidates,                                      Nemotron words it; every sentence
          a plain rule decides                                       is checked against the rows it cites
```

Java 25 and Spring Boot 4. All code is in `src/main/java/dev/tally/`:

| Code | What it does |
|---|---|
| `data/Backend`, `ZetarisBackend`, `H2Backend` | The only place that touches data. Zetaris (live) and H2 (offline) run the same SQL |
| `audit/Checks` | The six checks; `audit/Evidence` holds every row a finding cites |
| `audit/Investigation` | One audit as a stream of steps: the agent's trace |
| `memory/HmemMemory` + `memory/server.ts` | The controller's decisions, kept in Meterless H-MEM through a small sidecar |
| `audit/Explain` | The verifier's sentences, the model rewrite, and the claim check |
| `web/AuditController`, `web/` | The API (live trace over server-sent events, decisions, evidence packs, memo) and the pages |
| `landing/` | The landing page (React, GSAP, WebGL), built into `web/` |

## The six checks

| | Check | Systems joined | Rule |
|---|---|---|---|
| D1 | Duplicate payment | ERP, vendor master | Same vendor, same amount, paid within the policy's duplicate window |
| D2 | Vendor matches an employee | vendor master, HR, ERP | A vendor's bank account or address is an employee's; cleared only by a disclosed conflict approved by the Controller or CFO |
| D3 | Split to dodge approval | ERP, vendor master, HR | Same vendor, same requester, same day, each invoice under the approval limit, together over it |
| D4 | Payment not in the books | bank feed, ERP, vendor master | Money left the bank with no ERP payment behind it |
| D5 | New vendor, large payment | vendor master, ERP, HR | A payment at the CFO limit or above to a vendor younger than the policy's new-vendor window |
| D6 | Approved own invoice | ERP, vendor master, HR | Requester and approver are the same person |

## Measured

On the synthetic books in `data/` (fixed seed), offline mode, `./mvnw test`:

- **Run 1:** 6 of 6 planted frauds found, 0 other false alarms across 2,709 records. The split-shipment look-alike is flagged (it looks exactly like a duplicate). The disclosed conflict of interest is cleared by the vendor master's own record. $156,640.00 at risk. 32 SQL statements.
- **Live on Zetaris** (local platform, Lightning JDBC): the same results, 32 federated statements over the four datasources in about 9 seconds.
- **Run 2,** after the controller marks the split shipment expected: it is set aside with the controller's reason and the memory id; all 6 frauds are still flagged; $152,440.00 at risk.
- The tests also prove the claim check rejects an invented amount, an invented person, a reworded wrong date and a citation from another finding.

## Run it

### Offline, in two minutes (no accounts, no keys)

Needs Java 25.

```bash
git clone https://github.com/sidharthnair7/Tally && cd Tally
./mvnw spring-boot:run
```

Open http://localhost:8080, then **Run an audit**. Mark the Brennan Packaging finding **Expected…** with a reason,
then run again. `python data/generate.py` rebuilds the books (the one Python script: a data generator, not part of the app); `data/answer_key.json` is what the tests check against.

### Memory: Meterless H-MEM

```bash
cd memory && npm install && npm run setup && npm start
```

`npm run setup` downloads Meterless's open H-MEM reference engine into `memory/vendor/hmem`. The page's Memory badge
switches from "Local file" to "Meterless H-MEM" once the sidecar answers on port 8787.

### Live data: Zetaris

1. Install Zetaris locally (Zetaris Cloud, Run locally) and sign in.
2. Load the four systems into four Postgres databases: `python zetaris/load_postgres.py --container <postgres container> --user <user>`
3. Copy `.env.example` to `.env.local` and fill in the Zetaris JDBC values (`ZETARIS_JDBC_JAR` pointing at the bundle's `jdbc/` jar, username, password) and the Postgres ones (`PG_HOST=postgres`, `PG_USER`, `PG_PASSWORD`).
4. Register the four datasources: run `dev.tally.data.RegisterZetaris` (or paste `zetaris/register.sql` into the Zetaris SQL Editor).
5. Set `TALLY_BACKEND=zetaris` and restart. The Data badge shows "Zetaris · live".

### Explanations: NVIDIA Nemotron

Set `TALLY_LLM_BASE_URL`, `TALLY_LLM_MODEL` and `TALLY_LLM_API_KEY` in `.env.local` (see `.env.example`). Any
OpenAI-compatible endpoint works, including a Meterless Nightshift hotspot on your own machine.

## Sponsor technology

**Zetaris.** The one door to the data. Each of the company's systems is registered as its own datasource, and every
check is a single SQL statement that Zetaris federates across them: the bank feed against the ERP against the vendor
master, read in place. The page shows every statement, with the systems it touched, its row count and its time.

**Meterless.** The H-MEM engine is Tally's long-term memory of human judgment. A controller's decision is stored as
a memory (MemoryStoreService), found again on the next run by hybrid retrieval (MemoryRetrievalService), marked
helpful each time it sets a finding aside (feedback), and its full history is kept in the append-only TrustLedger,
which the page shows. That turns a one-shot checker into an auditor that learns the business.

**NVIDIA.** Nemotron is wired in as an optional writer for the explanations a controller reads (set `TALLY_LLM_*`).
It never decides a verdict, and every sentence it writes is checked against the rows it cites. The demo video runs
without it, so its sentences come from the verifier.

**What's unique:** the memory is of *decisions*, with a reason and an audit trail, so a suppressed alert is always
traceable to a person; and the model is fenced in by an evidence check, so the wording can be fluent without being
trusted blindly.

Cursor was not used. Tally was built with Claude Code as a coding assistant.

## Pre-existing components

None of Tally's own code existed before the build. Third-party, open source:

- Meterless H-MEM reference engine (Apache-2.0), downloaded by `npm run setup` and not stored in this repo. The sidecar loads it the way Meterless's agentathon starter does.
- The Zetaris REST call follows the shape in `zetaris/getting-started` (Apache-2.0).
- Spring Boot, H2, Jackson, JUnit, tsx; the landing page uses React, GSAP and vgpu.

## Data

All data is synthetic, generated by `data/generate.py` with a fixed seed: a fictional manufacturer, Brightwater
Components Inc., one quarter of books. Names, bank accounts (on made-up routing numbers) and addresses are invented.
Account numbers leave the server masked to their last four digits.

## Limits

- Address matching is exact after normalisation; fuzzy matching is next.
- A decision is remembered against the check plus the payee, so it never clears a different vendor.
- The hosted demo runs offline mode (the same SQL on H2); the Zetaris run is in the video.

## Next

ERP connectors (NetSuite, QuickBooks) as Zetaris datasources, more checks (round-number and weekend payments,
Benford tests), and overnight full-ledger sweeps on a Meterless Nightshift machine.

## Team

Sidharth Nair · Basu
