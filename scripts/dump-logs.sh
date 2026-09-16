#!/usr/bin/env bash
# Collect the latest workshop logs + Redis state into debug/ for offline analysis.
#
#   ./scripts/dump-logs.sh                 # last 30 min, 2000 lines per service
#   ./scripts/dump-logs.sh 10m 500         # last 10 min, 500 lines per service
#   APP_URL=http://localhost:8080 ./scripts/dump-logs.sh
#
# Output: debug/logs-<timestamp>/ (+ debug/latest symlink). debug/ is gitignored.
set -u

SINCE="${1:-30m}"
TAIL="${2:-2000}"
APP_URL="${APP_URL:-http://localhost:8080}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
RCLI=(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT")

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$ROOT/debug/logs-$STAMP"
mkdir -p "$OUT"

echo "==> collecting into debug/logs-$STAMP (since=$SINCE, tail=$TAIL)"

# 1. Docker Compose logs, one file per service
if command -v docker >/dev/null 2>&1; then
    ( cd "$ROOT" && docker compose ps --services 2>/dev/null ) | while read -r svc; do
        [ -z "$svc" ] && continue
        ( cd "$ROOT" && docker compose logs --no-color --timestamps \
            --since "$SINCE" --tail "$TAIL" "$svc" ) > "$OUT/docker-$svc.log" 2>&1
        echo "    docker-$svc.log ($(wc -l < "$OUT/docker-$svc.log") lines)"
    done
fi

# 2. Dev-mode logs (./mvnw spring-boot:run redirected to a file, or Spring's own file)
for candidate in "$ROOT/logs/"*.log "$ROOT/app.log" "$ROOT/nohup.out" /tmp/app.log; do
    [ -f "$candidate" ] && cp "$candidate" "$OUT/local-$(basename "$candidate")" 2>/dev/null \
        && echo "    local-$(basename "$candidate")"
done

# 3. Application state
{
    echo "### GET $APP_URL/api/health"
    curl -s -m 5 "$APP_URL/api/health" || echo "(unreachable)"
    echo; echo "### GET $APP_URL/api/cache/stats"
    curl -s -m 5 "$APP_URL/api/cache/stats" || echo "(unreachable)"
} > "$OUT/app-state.txt" 2>&1
echo "    app-state.txt"

# 4. Redis state: indexes, slowlog, memory, keyspace
if command -v redis-cli >/dev/null 2>&1; then
    {
        echo "### FT._LIST";        "${RCLI[@]}" FT._LIST
        echo; echo "### DBSIZE";    "${RCLI[@]}" DBSIZE
        echo; echo "### INFO server/memory/clients/stats/keyspace"
        "${RCLI[@]}" INFO server
        "${RCLI[@]}" INFO memory
        "${RCLI[@]}" INFO clients
        "${RCLI[@]}" INFO stats
        "${RCLI[@]}" INFO keyspace
        echo; echo "### SLOWLOG GET 25"
        "${RCLI[@]}" SLOWLOG GET 25
        echo; echo "### MEMORY DOCTOR"
        "${RCLI[@]}" MEMORY DOCTOR
    } > "$OUT/redis-state.txt" 2>&1
    echo "    redis-state.txt"
fi

# 5. Build/version context
{
    echo "### git";    git -C "$ROOT" log --oneline -5; git -C "$ROOT" status --short
    echo; echo "### docker compose ps"; ( cd "$ROOT" && docker compose ps )
} > "$OUT/context.txt" 2>&1
echo "    context.txt"

# 6. Errors only — the file to read first
grep -hiE "error|exception|caused by|500 |failed" "$OUT"/*.log 2>/dev/null \
    | tail -300 > "$OUT/errors.txt"
echo "    errors.txt ($(wc -l < "$OUT/errors.txt") lines)"

ln -sfn "logs-$STAMP" "$ROOT/debug/latest"

echo "==> done: debug/logs-$STAMP  (also debug/latest)"
echo "    start with: less debug/latest/errors.txt"
