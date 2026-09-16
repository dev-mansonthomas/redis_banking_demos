# Redis in 20 minutes — developer audience

- **Audience**: developers at an electricity transmission system operator (TSO)
- **Duration**: 20 min, live demo on `redis_banking_demos` (http://localhost:8080)
- **Scope**: AI use cases **excluded** (UC9, UC14, UC15, UC16, UC17)
- **Single takeaway**: *one engine, one latency budget — for caching, protection, coordination, ingestion, query and geo.*

Routes verified in `UseCaseController`: `GET /usecase/{id}` (id = 1..17).
Every page has a **⛶ Fullscreen** button (presenter mode).

---

## 1. Use case selection

Six non-overlapping demos, ordered to tell **one** story:
"you know Redis as a cache — here is why it became your real-time layer".

| # | UC | URL | Time | Primitive shown | Why this one (power-grid context) |
|---|---|---|---|---|---|
| 1 | **UC10 Cache-Aside** | `/usecase/10` | 2 min | `GET` / `SET EX`, hit ratio | Familiar ground: 200 ms → <1 ms, TTL = freshness for free |
| 2 | **UC4 Rate Limiting** | `/usecase/4` | 2 min | `INCR` + `EXPIRE`, sliding window | APIs exposed to BRPs / aggregators / partners |
| 3 | **UC13 Distributed Locking** | `/usecase/13` | 2.5 min | `SET NX EX` + Lua, 3-client contention | Coordination across instances: one job/order runs once |
| 4 | **UC11 Real-time Monitoring** | `/usecase/11` | 3.5 min | Streams `XADD` / `XRANGE` / `XTRIM` | Telemetry & measurements: ingest + live dashboard + anomaly |
| 5 | **UC8 Document DB + RQE** | `/usecase/8` | 3.5 min | `JSON.SET`, `FT.CREATE` / `FT.SEARCH` | Queryable reference data without a separate Elasticsearch |
| 6 | **UC12 Geospatial** | `/usecase/12` | 2.5 min | `GEOADD` / `GEOSEARCH`, `FT.SEARCH` geo + tag | Substations & assets on a map, field-crew dispatch, works geofencing |

Intro 1.5 min + closing 1.5 min = **19 min**. No slack: keep a timer visible.

> Budgets are tighter than the 5-demo version: UC10 and UC4 are deliberately rushed (familiar ground), and the time saved funds UC12.

### Deliberately dropped

| UC | Reason |
|---|---|
| UC1 / UC2 / UC3 (token, session, profile) | Same primitive as UC10 → Hash + TTL. One sentence covers it. |
| UC5 (dedup) | Same atomicity as UC13 (`SET NX`) → mentioned verbally during UC13. |
| UC6 / UC7 | Both are "real-time feature store"; UC6 also overlaps UC11's Streams. |
| UC9 / UC14 / UC15 / UC16 / UC17 | AI, out of scope (UC17 = consumer groups, mentioned verbally only). |

### Backup (if you gain 3 min)

**UC7 Feature Store** (`/usecase/7`) — features served in <1 ms for forecasting models (load, wind): `HSET` / `HINCRBY` / `FT.SEARCH`.

---

## 2. Narrative thread

### Intro — 1.5 min

> "What you see is a banking demo, but forget the payments: read 'measurements', 'market orders', 'partners'. The primitives are identical. 17 use cases, **one single Redis instance**. We'll open six."

Show the landing page + **RedisInsight in a second tab (port 8001)**:
"everything I do, you'll see it in the keys."

### 1 — UC10 · Familiar ground (2 min)

**Actions**: pick a product → **Fetch Product** = CACHE MISS (~200 ms) → **Fetch Product** again = CACHE HIT (<1 ms). Show the hit ratio, then **Clear Cache (this product)**.

**Say**: "the pattern isn't `GET`/`SET`, it's the **TTL**: freshness becomes a property of the data, not a cron job."

**Keys**: `uc10:product:*` (the simulated RDBMS sits under `uc10:mockdb:*`).

**Bridge**: "the same Hash + TTL backs tokens, sessions and profiles (UC1-3) — I won't open them, it's the same gesture."

### 2 — UC4 · Same counter, now a guardrail (2 min)

**Actions**: **Call API** two or three times → counter increments; **Burst (5 calls)** to hit the limit at once → denial in red; **↺ Reset**. Code tabs: *Fixed Window (demo)* vs *Sliding Window (alternative)*.

**Say**: "`INCR` is **atomic**: at 10,000 req/s spread over N instances, no race conditions, no application-level lock. The key is per partner, per API key, per IP — you choose the granularity."

**Keys**: `uc4:*`.

**Bridge**: "atomicity protects a resource. It can also guarantee ownership of one."

### 3 — UC13 · Coordination (2.5 min)

**Actions**: **Acquire Lock** → TTL counts down → **Acquire Lock** with a different client ID = denied (`NX` guarantee) → **Release Lock** → **Simulate 3 Concurrent Clients** (three race, one wins). Show the Lua script in the code panel; open *WATCH/MULTI/EXEC (alternative)* only if asked.

**Say**: "`NX` = mutual exclusion, `EX` = no deadlock if the holder crashes, Lua = only the holder releases. That's what stops two instances from replaying the same processing."

**Keys**: `uc13:lock:*`.

**Bridge**: "same primitive for ingestion idempotency (UC5: a replayed message is processed once). And now, the ingestion itself."

### 4 — UC11 · Redis becomes the stream (3.5 min) — **peak of the talk**

**Actions**: **Start Simulation**, let the chart build up (~2 TPS), comment on TPS / average amount, then **Inject Anomaly** → spike + high-risk % jump. **Stop**, then **Reset**.

**Say**: "Streams are an append-only log with ms-precision timestamp IDs → time-window queries are native. `XTRIM MAXLEN` bounds memory: retention is a parameter, not a project. Here the aggregation is in Java, but **consumer groups** let N workers share the stream with acknowledgement (`XACK`) — that's what powers UC17."

**Key**: `uc11:stream:transactions` (show it in RedisInsight while it runs).

**Bridge**: "we have data in motion. What's left is the data you query."

### 5 — UC8 · One datastore (3.5 min)

**Actions**: **CRUD Operations** tab → *Create Document* (`JSON.SET`), *Read* (`JSON.GET`), *Read Field* (`JSON.GET $.title`) → **Full-Text** tab → free-form query with relevance scores. If you're short on time, skip the *Query* tab (`@category:{PSD2}`).
**Do not open the *Vector* and *Hybrid* tabs** (AI scope excluded).

**Say**: "native JSON plus secondary indexes: tag, numeric, full-text, geo. The categories here are financial regulations; in your world they'd be grid codes, procedures, asset sheets. The question isn't 'Redis or Elasticsearch', it's 'how many systems can I remove from my architecture'. Under 10 ms."

**Keys**: `uc8:doc:*`.

**Bridge**: "I said 'and geo' in that list of index types. That wasn't filler."

### 6 — UC12 · Geo in the same engine (2.5 min) — **visual close**

**Actions**:
1. **Native Geospatial** tab → **Puerta del Sol** preset → 2 km radius → **Search**: points appear on the map with their distance.
2. **JSON + Query Engine** tab → same location → type filter **ATM** → **Search**: the query becomes `FT.SEARCH` with `@location:[lng lat 2 km] @type:{atm}`.
3. Add a service filter (e.g. **Advisor**) → a single query, zero application-side filtering.

**Say**: "Two paths. Native — `GEOADD` / `GEOSEARCH` — is a Sorted Set, O(N+log(M)), unbeatable for pure proximity. The Query Engine gives you geo **combined** with any other criterion in **one query**: radius + asset type + available service + time window. For you: which substation in this radius, with which equipment, which crew available. And it's the **same instance** as the previous five demos — no separate geo database."

**Keys**: `uc12:geo:atms` (the geo Sorted Set), `uc12:branch:*`, `uc12:meta:*`.

### Closing — 1.5 min

1. Caching, rate limiting, locking, streaming, query, geo: **one instance, six roles** — and you saw 6 of 17.
2. The real saving isn't latency, it's the **number of components** you operate, monitor and patch.
3. What we didn't open: the AI side (agent memory, RAG, semantic cache, guardrails) — same instance, same vector index. Material for a follow-up session.
4. **Active-Active** multi-region for availability / geo constraints.
5. Repo + presenter guide available: everything comes back up with `make demo`.

---

## 3. Stage discipline

- If a demo runs **more than 30 s** over budget: cut it, move on, never backtrack.
- Planned sacrifices if you fall behind: UC8's *Query* tab, then UC12 step 3 (service filter). **Do not drop UC12 entirely** — it's the visual close.
- Keep RedisInsight on `uc4:*` / `uc11:stream:transactions` — that's what makes "this really is Redis" credible.
- **Never open an AI use case "just to show it"** — that's the one way to lose 5 minutes.
- Use **⛶ Fullscreen** on each demo page (presenter mode).

## 4. Pre-demo checklist

- [ ] `docker compose ps` — `redis`, `app`, `redis-insight` up
- [ ] http://localhost:8080 responds, landing page fine
- [ ] RedisInsight http://localhost:8001 connected to the workshop instance
- [ ] UC10: **Clear All Cache** done (guarantees a MISS on the opening click)
- [ ] UC11: simulation stopped and **Reset** done
- [ ] UC13: no leftover lock (`uc13:lock:*` empty)
- [ ] RQE indexes present: `redis-cli FT._LIST | grep -E 'uc8|uc12'` → `idx:uc8:documents` + `idx:uc12:branches`
- [ ] UC12: map tiles load (reachable from the venue network — **test it on site**, keep a fallback screenshot)
- [ ] Tabs open in order: `/usecase/10` → `/4` → `/13` → `/11` → `/8` → `/12`
- [ ] Timer visible, OS notifications muted
