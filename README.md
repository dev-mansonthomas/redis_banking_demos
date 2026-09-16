# Redis Banking Workshop Demo

Spring Boot 3.x workshop application showcasing **17 Redis use cases** for banking.
Each use case includes a **live interactive demo** and a **code showcase panel** with curated Redis snippets.

## Use Cases

| # | Demo | Key Redis Features |
|---|------|--------------------|
| 1 | Authentication Token Store | Hash, TTL, HSET, HGET |
| 2 | Session Storage | Hash, TTL, HGETALL |
| 3 | User Profile Storage | Hash, HSET, HGETALL, HINCRBY |
| 4 | Rate Limiting (Open Banking / PSD2) | String INCR, EXPIRE |
| 5 | Transaction Deduplication | SET NX EX, Hash, TTL |
| 6 | Fraud Detection (Risk Scoring) | Sorted Set, Streams, RQE |
| 7 | Feature Store | Hash, TTL, RQE |
| 8 | Document Database | JSON, RQE, Vector, Full-text |
| 9 | AI Banking Assistant (Memory + RAG + Semantic Cache) | Hash, Vector, JSON, TTL, Streams |
| 10 | Cache-Aside Pattern | String GET/SET EX, DEL |
| 11 | Real-time Transaction Monitoring | Streams, XADD, XRANGE, XLEN |
| 12 | ATM & Branch Finder (Geospatial) | Geo, JSON, RQE, GEOSEARCH |
| 13 | Distributed Locking | SET NX EX, Lua, EVAL, TTL |
| 14 | Agent Memory Server | AMS, REST, MCP, Context Assembly |
| 15 | AI Guardrails for Banking Chat | Vector, Streams, INCR, Hash |
| 16 | AI Gateway (Routing + Semantic Cache + Observability) | Vector, Semantic Cache, INCR, Hash, Streams |
| 17 | AI Agent Coordination | Streams, Consumer Groups, XREADGROUP, XACK, Hash |

## Screenshots

### Landing Page
![Landing Page](docs/screenshots/landing-page.png)

### UC9: AI Banking Assistant (Memory + RAG + Semantic Cache)
![UC9 AI Assistant](docs/screenshots/uc9-ai-assistant.png)

### UC13: Distributed Locking
![UC13 Distributed Locking](docs/screenshots/uc13-distributed-locking.png)

### UC17: AI Agent Coordination
![UC17 Agent Coordination](docs/screenshots/uc17-agent-coordination.png)

UC17 shows a **6-phase agent lifecycle** (`start → thinking → searching → tools → reasoning → done`) with **staggered parallel execution** and **per-agent progress indicators**, making the Redis Streams coordination flow easy to follow in a live workshop.

## Prerequisites

- Java 17+ (for development mode)
- Docker and Docker Compose v2

## Quick Start

### Option 1: Development mode (hot reload)

Best for developing and modifying the workshop. Requires Java 17+.

```bash
# 1. Start Redis
docker compose up -d

# 2. Start the app (with hot reload)
./mvnw spring-boot:run

# 3. Open http://localhost:8080
```

> **Note**: JVM flags in `.mvn/jvm.config` automatically suppress Netty warnings on JDK 21+.

### Option 2: Workshop mode (no Java required)

Best for presenting the workshop. Everything runs in Docker — no local Java needed.

```bash
# 1. Copy the tracked env template
cp .env.example .env

# 2. Edit .env and set OPENAI_API_KEY if you want AI responses in UC9/UC14/UC15/UC16/UC17

# 3. Build and start the full demo stack
make demo

# 4. Open http://localhost:8080
```

`make demo` uses Docker Compose, and Docker Compose automatically reads `.env` from the repository root.
If `OPENAI_API_KEY` is left empty, non-AI use cases still work, but UC9, UC14, UC15, UC16, and UC17 will return a configuration error at the LLM step.

To stop everything:

```bash
make demo-down
```

## Docker Setup

### Services

| Service | Image | Port | Description |
|---------|-------|------|-------------|
| `redis` | `redis:latest` | 6379 | Redis 8 with RQE, JSON, Search, Vector support |
| `agent-memory-server` | `redislabs/agent-memory-server:latest` | 8000 | Redis Agent Memory Server — REST API, single-process (`asyncio` backend), auth disabled for local workshop |
| `agent-memory-server-mcp` | `redislabs/agent-memory-server:latest` | 9000 | Agent Memory Server MCP endpoint (streamable HTTP) — only started with `--profile ams-mcp` |
| `app` | Built from `Dockerfile` | 8080 | Spring Boot application (workshop profile only) |
| `redis-insight` | `redis/redisinsight:latest` | 8001 | Visual Redis browser (starts by default on port 8001) |

### Environment file (`.env`)

Docker Compose reads `.env` automatically from the repository root, and `make demo` reuses that same Compose configuration.

Setup flow:

```bash
cp .env.example .env
# then edit .env and replace the placeholder value
```

- `.env.example` is committed as the safe template.
- `.env` is already ignored by Git and is where you put your real `OPENAI_API_KEY`.
- `OPENAI_API_KEY` is passed to the workshop app and Agent Memory Server containers during Docker runs.

### Agent Memory Server (local workshop)

The `agent-memory-server` service is started by default alongside Redis. It exposes the AMS REST API at `http://localhost:8000`:

```bash
# Start Redis + AMS REST (default)
docker compose up -d

# Smoke test
curl -s http://localhost:8000/v1/health

# Optionally expose the MCP endpoint (streamable HTTP) on port 9000
docker compose --profile ams-mcp up -d

# Workshop mode: Redis + AMS + App + Redis Insight
docker compose --profile workshop up -d --build
```

Spring reads the AMS URL from environment variables with sensible local defaults (`src/main/resources/application.yml`):

| Property | Env var | Default |
|----------|---------|---------|
| `workshop.ams.base-url` | `WORKSHOP_AMS_BASE_URL` | `http://localhost:8000` |
| `workshop.ams.mcp-url` | `WORKSHOP_AMS_MCP_URL` | `http://localhost:9000` |
| `workshop.ams.default-namespace` | — | `workshop` |

Notes:
- Auth is disabled (`DISABLE_AUTH=true`) and the `asyncio` task backend is used so no separate worker container is needed — this is a local workshop setup, not a production deployment.
- AMS stores its data in the same Redis instance used by the workshop. UC9 uses the `workshop:*` key namespace; AMS uses its own separate namespace, so they do not collide.
- To roll back, remove the `agent-memory-server` / `agent-memory-server-mcp` services from `docker-compose.yml` and unset the `WORKSHOP_AMS_*` env vars.

### Redis container

The Redis container starts automatically with a health check (`redis-cli ping`). Data is persisted in a Docker volume (`redis-data`).

```bash
# Start Redis only
docker compose up -d

# Check Redis is healthy
docker compose ps

# Connect to Redis CLI
docker exec -it redis-workshop redis-cli

# Stop Redis (data persists in volume)
docker compose down

# Stop Redis and delete data
docker compose down -v
```

### App container (workshop profile)

The app uses a multi-stage Dockerfile:
1. **Build stage**: Maven + JDK 21 — compiles the project
2. **Runtime stage**: JRE 21 only — runs the JAR (~200MB image)

The app container automatically connects to the Redis container by service name (`SPRING_DATA_REDIS_HOST=redis`).

### Redis Insight

[Redis Insight](https://redis.io/insight/) provides a visual interface to inspect keys, run commands, and monitor Redis in real time.

It now starts by default with Docker Compose:

```bash
docker compose up -d
# Redis Insight available at http://localhost:8001
```

The workshop Redis connection is pre-configured. Open `http://localhost:8001` and the database is already there as `Workshop Redis`.

## Redis Connection

### Default (local Docker)

The app connects to `localhost:6379` by default. This works when Redis runs via Docker Compose.

### Remote Redis / Redis Cloud

Override the connection with environment variables:

```bash
# Development mode
SPRING_DATA_REDIS_HOST=your-redis-host.cloud.redislabs.com \
SPRING_DATA_REDIS_PORT=12345 \
./mvnw spring-boot:run

# Workshop mode (Docker)
SPRING_DATA_REDIS_HOST=your-redis-host SPRING_DATA_REDIS_PORT=12345 \
docker compose --profile workshop up -d --build
```

For Redis Cloud with authentication, add to `application.yml`:

```yaml
spring:
  data:
    redis:
      host: your-redis-host.cloud.redislabs.com
      port: 12345
      password: your-password
```

## LLM Integration (OpenAI for AI responses)

### How it works

Embeddings are now **100% local** across the workshop using **BGE-small-en-v1.5** (384 dimensions, ONNX in-process).
You do **not** need an API key for vector search, RAG retrieval, semantic cache lookup, or document indexing.

OpenAI is **optional for the overall workshop** and required only for the **LLM response step in UC9, UC14, UC15, UC16, and UC17**.
Non-AI use cases (**UC1-UC8, UC10-UC13**) work without any API key.

| Mode | Non-AI UCs (UC1-UC8, UC10-UC13) | AI UCs (UC9, UC14, UC15, UC16, UC17) |
|------|-----------------------------------|----------------------------------------|
| **Without API key** | Fully functional, including local embeddings and vector search | AI UCs show a clear error at the LLM response step; Redis features such as vector search, RAG retrieval, semantic cache, guardrails, routing, and streams still work |
| **With API key** | No change | Same Redis pipelines, plus real chat completions via GPT-4o-mini |

**Bottom line**: Non-AI use cases run without any API key. AI use cases need `OPENAI_API_KEY` only for LLM-generated responses; local BGE embeddings and Redis-side retrieval/caching still work without it.

### Setting up OpenAI

1. Get an API key from [platform.openai.com](https://platform.openai.com/api-keys)

2. Preferred setup: copy `.env.example` to `.env`, then replace the placeholder with your real key. `docker compose` and `make demo` will pick it up automatically from the repo root:

```bash
cp .env.example .env
```

3. You can also set the environment variable inline if you want LLM responses in UC9, UC14, UC15, UC16, and UC17:

```bash
# Development mode
OPENAI_API_KEY=sk-... ./mvnw spring-boot:run

# Makefile shortcut
OPENAI_API_KEY=sk-... make dev

# Workshop mode (Docker) — pass to the app container
OPENAI_API_KEY=sk-... docker compose --profile workshop up -d --build
```

4. On startup, the app logs whether OpenAI chat is enabled. Local BGE embeddings always run in-process and do not require configuration:

```
OpenAI chat integration enabled (model=gpt-4o-mini)
```

Or if no key is set:

```
OpenAI chat integration disabled — no API key configured. AI features will return a configuration error.
```

### Models used

| Purpose | Model | Used by |
|---------|-------|---------|
| Chat completions | `gpt-4o-mini` | UC9 AI Assistant, UC14 Agent Memory Server, UC15 Guardrails, UC16 AI Gateway, UC17 Agent Coordination |
| Text embeddings | `BGE-small-en-v1.5` (384 dimensions, ONNX) | UC8 vector search, UC9 RAG + semantic cache |

`application.yml` only configures optional OpenAI chat:

```yaml
openai:
  api-key: ${OPENAI_API_KEY:}
  model: gpt-4o-mini          # or gpt-4o, gpt-3.5-turbo
```

### Cost estimate

Only chat tokens are billable. A typical UC9 demo session (10-20 questions) costs < $0.01 with gpt-4o-mini.

## Monitor endpoint

`GET /api/monitor` is a JSON metrics endpoint that returns detailed Redis metrics:

```json
{
  "redis_version": "8.0-M02",
  "uptime_seconds": 12345,
  "connected_clients": 3,
  "used_memory_human": "2.5M",
  "used_memory_peak_human": "3.1M",
  "total_commands_processed": 45678,
  "instantaneous_ops_per_sec": 12,
  "keyspace_hits": 1234,
  "keyspace_misses": 56,
  "db_size": 342
}
```

## Persistence: intentionally disabled

The Redis container runs with `--save "" --appendonly no`: **no RDB snapshot, no AOF**.
Workshop data is disposable — the application recreates every dataset and every index at
startup (`@PostConstruct` loaders) and on demand via `POST /api/reset-all`.

Why this matters for a live demo:

- A full disk used to break the whole demo. With snapshots on, a failed `BGSAVE` plus the
  default `stop-writes-on-bgsave-error yes` makes Redis **reject every write**, so any use case
  that writes (cache `SET`, rate-limit `INCR`, lock `SET NX`, stream `XADD`) returns HTTP 500
  while read-only use cases keep working. No snapshot, no such failure mode.
- Every run starts from the same clean state, so demos are reproducible and no stale keys or
  half-built indexes survive from a previous session.

Consequence: if the **Redis container** is restarted while the app is running, the data is gone
and the app will not reload it by itself — restart the app, or click **Reset All Data** in the
navbar (`POST /api/reset-all`). Restarting the *app* alone is always safe.

Cold start measured on an empty Redis: app healthy in ~7 s, 1648 keys, 11 search indexes.

## Collecting logs for debugging

When something fails (HTTP 500 on a use case, a missing index, a slow query), dump everything
a diagnosis needs into `debug/` with one command:

```bash
./scripts/dump-logs.sh              # last 30 min, 2000 lines per service
./scripts/dump-logs.sh 10m 500      # last 10 min, 500 lines per service
```

It writes `debug/logs-<timestamp>/` (plus a `debug/latest` symlink) containing:

| File | Content |
|------|---------|
| `errors.txt` | every ERROR / Exception / "Caused by" line — **read this first** |
| `local-app.log` | the Spring Boot log file (`logs/app.log`, written in both dev and Docker mode) |
| `docker-<service>.log` | `docker compose logs` per running service (app, redis, agent-memory-server, redis-insight) |
| `app-state.txt` | `/api/health` and `/api/cache/stats` responses |
| `redis-state.txt` | `FT._LIST`, `DBSIZE`, `INFO`, `SLOWLOG GET 25`, `MEMORY DOCTOR` |
| `context.txt` | last 5 commits, working-tree status, `docker compose ps` |

`debug/` and `logs/` are gitignored. Override the Redis target with
`REDIS_HOST` / `REDIS_PORT`, and the app URL with `APP_URL`.

## Project Structure

```
.mvn/
└── jvm.config                      # JVM flags (suppresses Netty warnings on JDK 21+)

src/main/java/com/redis/workshop/
├── WorkshopApplication.java        # Spring Boot entry point
├── config/
│   ├── AiGatewayDataLoader.java    # UC16 mock routing + cache seed data
│   ├── RedisConfig.java            # Lettuce connection pool
│   └── DocumentDataLoader.java     # PDF chunks + vector index loader
├── controller/
│   ├── AiGatewayController.java    # UC16: AI gateway demo endpoints
│   ├── AgentCoordinatorController.java # UC17: multi-agent coordination demo
│   ├── GuardrailsController.java   # UC15: AI guardrails demo endpoints
│   ├── HomeController.java         # Landing page
│   └── UseCaseController.java      # Use case routing
├── service/                        # Business logic (per use case)
│   ├── AgentCoordinatorService.java # UC17: Streams orchestration + SSE updates
│   ├── AiGatewayService.java       # UC16: routing, cache, observability
│   ├── LocalEmbeddingService.java  # Local BGE-small-en-v1.5 embeddings (384d, ONNX)
│   ├── OpenAiService.java          # Optional OpenAI chat client
│   ├── AssistantService.java       # UC9: AI agent memory + RAG
│   ├── GuardrailsService.java      # UC15: guardrail pipeline + audit trail
│   └── ...                         # One service per use case
└── tools/                          # Offline utilities
    ├── EmbeddingGenerator.java     # Generate local BGE embeddings from PDFs
    └── PdfChunker.java             # PDF → text chunks

src/main/resources/
├── application.yml                 # Redis + optional OpenAI chat config
├── data/kb-embeddings.json         # Pre-generated document chunks + local vectors
├── docs/*.pdf                      # Regulation PDFs (PSD2, DORA, MiFID II, GDPR, EU AI Act)
├── templates/
│   ├── layout.html                 # Shared layout with nav
│   ├── index.html                  # Landing page (17 cards)
│   ├── guide.html                  # Workshop Presenter Guide
│   └── usecase-{1..17}.html        # Use case pages
└── static/
    ├── css/redis-brand.css         # Redis brand design tokens
    ├── img/icons/{light,dark}/     # Redis brand icons per theme
    ├── js/main.js                  # Dark mode toggle + utils
    ├── js/qr-landing.js            # Landing page QR code renderer
    ├── js/usecase-{1..17}.js       # Per-use-case JS
    ├── vendor/prism/               # Syntax highlighting
    └── vendor/qrcode/              # qrcode-generator library (landing QR)
```

## Stack

- **Backend**: Spring Boot 3.4.x, Spring Data Redis (Lettuce), Java 17+
- **Frontend**: Thymeleaf + Vanilla JS, Redis brand CSS, Prism.js
- **Database**: Redis 8 (RQE, JSON, Search, Vector support)
- **LLM / embeddings**: OpenAI GPT-4o-mini (optional chat) + BGE-small-en-v1.5 (ONNX, local embeddings)

## UI Features

- **Redis brand icons** — Each use case card uses official Redis PNG icons with automatic light/dark theme switching
- **Dark/light mode** — Clean SVG toggle (sun/moon) with system preference detection
- **No emojis** — All UI elements use text labels and SVG icons for a professional look
- **Code showcase** — Each use case includes curated Java + Redis CLI snippets with Prism.js syntax highlighting
- **Responsive layout** — Two-panel grid (demo + code) that adapts to mobile
- **Presenter guide** — Built-in guide at `/guide` with talking points and demo steps for each use case
- **Redis Monitor** — JSON API endpoint at `/api/monitor` exposing memory usage, key count, ops/sec, hit rate, uptime, and connected clients. Live Redis commands executed by each use case are shown inline via the collapsible "Redis Commands Executed" panel on each UC page.
- **Presentation mode** — Toggle button on all use case pages that hides the code panel and expands the demo to full width. State persists across page navigation via localStorage.
- **QR code** — Landing page displays a scannable QR code linking to the GitHub repository, generated client-side via vendored qrcode-generator library.
