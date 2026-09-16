# Spec — Demo robustness fixes (post-Elia session)

Backlog of defects found while preparing the 20-minute Elia demo (2026-09-14/16).
Nothing here blocks the demo: the two bugs that did (UC12 missing index, UC12 fullscreen map)
are fixed on `fix/uc12-geo-index-and-fullscreen-map` (`11b531d`), and Redis persistence is off
(`037f394`) so the MISCONF write-outage cannot recur.

Priorities: **P1** = same class of bug as a defect that already broke a live demo.
**P2** = wrong behaviour, misleads diagnosis. **P3** = hardening / polish. **P4** = docs.

---

## P1-A — Four loaders still skip index creation when data survives without its index

### Problem
`GeoFinderService` (UC12) and `DocumentDataLoader` (UC8) were fixed by adding an
`indexExists()` guard to the skip condition. The same pattern is still present in four
loaders, so a Redis instance holding the JSON/Hash keys but no index definition makes them
short-circuit and the index is never (re)created:

| Service | Index(es) | Current skip condition |
|---|---|---|
| `MemoryService` | `idx:uc9:memory` | `existingMemoryDocCount()` = `max(indexDocCount, countKeys)` |
| `KnowledgeBaseService` | `idx:uc9:kb` | `existingKbDocCount()` = `max(indexDocCount, countKeys)` |
| `GuardrailsService` | `idx:uc15:routes`, `idx:uc15:injections` | `(routeDocs >= 5 \|\| routeKeys >= 5) && …` |
| `AiGatewayService` | `idx:uc16:routes`, `idx:uc16:cache`, `idx:uc16:guardrail:*` | `(routeDocs >= n \|\| routeKeys >= n) && …` |

In both shapes (`max(...)` and `||`) the key count alone satisfies the condition, so a missing
index is invisible to the guard.

### Evidence
The stale workshop database exposed exactly this: `FT._LIST` returned **8** indexes, while a
cold start on an empty Redis produces **11**. Missing were `idx:uc12:branches` (the reported
`SEARCH_INDEX_NOT_FOUND`), `idx:uc9:kb` and `idx:uc9:memory` — i.e. UC9 was silently degraded
too.

### Fix
Add `&& RedisStartupHelper.indexExists(redis, <INDEX>)` to each skip condition, and when the
data is present but an index is missing, recreate the index alone rather than reloading the
dataset (the pattern already used in `GeoFinderService.init()`).

### Acceptance criteria
- For each of the six indexes above: with data loaded, `FT.DROPINDEX <idx>` then restart the
  app → the index is recreated, a `WARN` names it, and the dataset is **not** reloaded.
- `FT._LIST` after any restart contains the 11 expected indexes.
- Existing 116 tests still pass.

### TDD steps
1. Failing unit test per loader: mock `StringRedisTemplate` so key counts are above threshold
   and `FT.INFO` throws → assert the index creation path runs.
2. Apply the guard.
3. Integration check with the real Redis container (drop → restart → `FT._LIST`).

---

## P1-B — `/api/health` does not report index state

### Problem
A missing index is only discoverable by reading `FT._LIST` by hand — which is how the UC12
failure was found, *during* demo prep. The health endpoint reports Redis, OpenAI and
embeddings, but nothing about the search indexes the demo depends on.

### Fix
Add an `indexes` section to `GET /api/health`: for each expected index, `present` +
`numDocs`. Overall status becomes `DEGRADED` when one is missing. Source the expected list
from a single registry (constant per owning service, collected by the health service) so a new
use case cannot forget to register.

### Acceptance criteria
- `curl -s localhost:8080/api/health | jq '.indexes'` lists the 11 indexes with doc counts.
- Dropping one index flips `status` to `DEGRADED` and marks that index `present: false`.
- Pre-demo checklist in `docs/presentations/elia-20min.md` replaced by this single call.

---

## P2-A — Unknown API paths return HTTP 500 instead of 404

### Problem
`NoResourceFoundException` (Spring's static-resource fallback) has no handler in
`GlobalExceptionHandler`, so it lands in the catch-all and is logged as
`ERROR … Unhandled exception` with a stack trace, and returned as **500**.

### Evidence
```
/api           -> 500   {"error":"No static resource api.","status":500}
/api/nope      -> 500
/api/docs/999  -> 404   (correct, handled by the controller)
```
In the collected dump, 65 of the ~300 error lines were this noise (internet scanners probing
`/api` and `/apis/authorization.k8s.io/v1/selfsubjectaccessreviews`), which actively hid the
real `MISCONF` stack trace during triage.

### Fix
Handle `NoResourceFoundException` → 404 with the standard JSON body, logged at `DEBUG`.
Keep `NoHandlerFoundException` as is.

### Acceptance criteria
- `/api/nope` → 404, body `{"error": …, "status": 404, "path": "/api/nope"}`.
- No `ERROR` line emitted for it; `dump-logs.sh`'s `errors.txt` stays free of scanner noise.
- Existing 404 behaviour of real controllers unchanged.

---

## P2-B — `createIndex()` reports genuine failures as "may already exist"

### Problem
`GeoFinderService.createIndex()` (line ~173) wraps `FT.CREATE` in
`catch (Exception e) { log.warn("Index {} may already exist: {}", …) }`. A real failure
(bad schema, wrong field path, OOM) is therefore indistinguishable from the benign
"already exists" case, and startup continues with a broken use case. The preceding blind
`FT.DROPINDEX` in a swallowed `try` has the same weakness.

### Fix
Create only when `indexExists()` is false, and assert the result with `FT.INFO` afterwards:
log `ERROR` (and surface via P1-B) when the index is still absent. Audit the other
`createIndex()` implementations for the same swallow.

### Acceptance criteria
- Temporarily corrupting the schema makes startup log an `ERROR` naming the index, and
  `/api/health` reports it missing.
- Normal startup logs exactly one line per created index and none when all exist.

---

## P3-A — No regression test for the index-bootstrap path

### Problem
The bug that broke UC12 in front of a demo audience is not covered by any of the 116 tests;
`RedisStartupHelper.indexExists()` is new and untested.

### Fix
Unit tests for `indexExists()` (FT.INFO ok / throwing), plus the per-loader tests from P1-A.
Consider one Testcontainers-backed test that drops an index and asserts recreation, if the
build's runtime budget allows it.

---

## P3-B — Presenter fullscreen: vertical space and selector scope

### Findings (measured, not speculative)
- The generic `resize` broadcast added to `setPresenterMode()` works beyond Leaflet: UC11's
  Chart.js canvas goes 536 px → 1854 px on toggle. No extra per-page listener needed.
- The canvas **height** stays 200 px in fullscreen, so UC11 — the peak of the talk — wastes
  most of a projector's vertical space. Mirror the map rule with a presenter-mode height
  (`≈45vh`) for demo canvases.
- `body.presenter-fullscreen #map` is a UC12-specific ID selector living in the shared
  `redis-brand.css`. Scope it to a class (`.demo-map`) when a second map appears.

### Acceptance criteria
- UC11 canvas ≥ 40 % of viewport height in presenter mode; chart still readable in normal mode.
- No change to non-presenter layouts (visual check on the six demo pages).

---

## P3-C — `dump-logs.sh` misses stopped containers

### Problem
The script iterates `docker compose ps --services`, which lists services with a **running**
container. A service that crashed before the dump contributes no log file — the most
interesting case.

### Fix
Enumerate from `docker compose config --services` (static list) and keep
`docker compose logs <svc>`, which works for stopped containers. Add `docker compose ps -a` to
`context.txt`.

### Acceptance criteria
- Stop `workshop-app`, run the script → `docker-workshop-app.log` is still produced.

---

## P4 — Document the MISCONF symptom → cause mapping

Persistence is now off, so the write-outage cannot recur in this stack, but the symptom is
worth a README troubleshooting entry: *every writing use case returns 500 while read-only ones
work* → check `redis-cli INFO persistence | grep rdb_last_bgsave_status` and free disk space.
Include the `docker builder prune -f` / `docker image prune -f` / `vm-clean --docker` recovery.

---

## Suggested sequencing (one branch, TDD, ~2 h)

| Step | Item | Why first |
|---|---|---|
| 1 | P2-A (404) | Isolated, ~10 min, immediately cleans the logs used to diagnose the rest |
| 2 | P1-A (4 loaders) | Same defect class as the live failure; test-first |
| 3 | P1-B (health indexes) | Turns the whole class into something a single curl detects |
| 4 | P2-B (createIndex assertion) | Builds on the P1-B registry |
| 5 | P3-A (tests) | Locks in 2-4 |
| 6 | P3-B, P3-C, P4 | Polish, no behaviour risk |

Branch off `main` **after** `fix/uc12-geo-index-and-fullscreen-map` is merged, to avoid
conflicting edits in `RedisStartupHelper` and `GeoFinderService`.
