#!/usr/bin/env bash
# Shared helpers for deploy/perf scripts (LocalStack / AWS).
set -euo pipefail

PERF_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${PERF_ROOT}/../.." && pwd)"

: "${BASE_URL:=http://localhost:8080}"
: "${AWS_REGION:=sa-east-1}"
: "${AWS_ENDPOINT_OVERRIDE:=http://localhost:4566}"
: "${SQS_QUEUE_URL:=http://localhost:4566/000000000000/transacoes-financeiras-processadas}"
: "${SQS_DLQ_URL:=http://localhost:4566/000000000000/transacoes-financeiras-processadas-dlq}"
: "${AWS_ACCESS_KEY_ID:=test}"
: "${AWS_SECRET_ACCESS_KEY:=test}"

export AWS_REGION AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_DEFAULT_REGION="$AWS_REGION"

aws_local() {
  # Git Bash otherwise rewrites file://C:/... args before aws.exe sees them.
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
    aws --endpoint-url "${AWS_ENDPOINT_OVERRIDE}" --region "${AWS_REGION}" "$@"
}

require_cmd() {
  local c
  for c in "$@"; do
    command -v "$c" >/dev/null 2>&1 || {
      echo "ERROR: required command not found: $c" >&2
      exit 1
    }
  done
}

wait_ready() {
  local url="${BASE_URL}/actuator/health/readiness"
  local i
  echo "Waiting for readiness at ${url} ..."
  for i in $(seq 1 60); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "Ready."
      return 0
    fi
    sleep 2
  done
  echo "ERROR: readiness never became healthy" >&2
  exit 1
}

queue_depth() {
  aws_local sqs get-queue-attributes \
    --queue-url "${SQS_QUEUE_URL}" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --output text \
    --query 'Attributes.[ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible]' \
    | awk '{print ($1+0)+($2+0)}'
}

# Visible + in-flight + delayed (full backlog for DelaySeconds publishes).
queue_depth_all() {
  aws_local sqs get-queue-attributes \
    --queue-url "${SQS_QUEUE_URL}" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible ApproximateNumberOfMessagesDelayed \
    --output text \
    --query 'Attributes.[ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible,ApproximateNumberOfMessagesDelayed]' \
    | awk '{print ($1+0)+($2+0)+($3+0)}'
}

queue_delayed() {
  aws_local sqs get-queue-attributes \
    --queue-url "${SQS_QUEUE_URL}" \
    --attribute-names ApproximateNumberOfMessagesDelayed \
    --output text \
    --query 'Attributes.ApproximateNumberOfMessagesDelayed' \
    | awk '{print $1+0}'
}

# Sleep until epoch seconds (used to release a DelaySeconds backlog before measuring consume EPS).
wait_until_epoch() {
  local target_epoch="$1"
  local now
  now=$(date +%s)
  if (( now >= target_epoch )); then
    return 0
  fi
  local sleep_for=$((target_epoch - now))
  echo "Waiting ${sleep_for}s for delayed backlog to become visible (until $(date -u -d "@${target_epoch}" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo "epoch ${target_epoch}")) ..."
  sleep "$sleep_for"
}

dlq_depth() {
  aws_local sqs get-queue-attributes \
    --queue-url "${SQS_DLQ_URL}" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --output text \
    --query 'Attributes.[ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible]' \
    | awk '{print ($1+0)+($2+0)}'
}

# Clear leftover invalid / in-flight noise from prior failed runs (LocalStack-friendly).
purge_perf_queues() {
  echo "Purging SQS source + DLQ ..."
  aws_local sqs purge-queue --queue-url "${SQS_QUEUE_URL}" >/dev/null || true
  aws_local sqs purge-queue --queue-url "${SQS_DLQ_URL}" >/dev/null || true
  # Purge is eventually consistent; wait until both are empty or timeout.
  local i depth
  for i in $(seq 1 15); do
    depth=$(( $(queue_depth) + $(dlq_depth) ))
    if [[ "$depth" -eq 0 ]]; then
      echo "Queues empty."
      return 0
    fi
    sleep 1
  done
  echo "WARN: queues still non-empty after purge (source=$(queue_depth) dlq=$(dlq_depth))" >&2
}

# Visible + in-flight only (seed can drain while a DelaySeconds main backlog stays hidden).
queue_undelayed_depth() {
  aws_local sqs get-queue-attributes \
    --queue-url "${SQS_QUEUE_URL}" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --output text \
    --query 'Attributes.[ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible]' \
    | awk '{print ($1+0)+($2+0)}'
}

wait_undelayed_drain() {
  local timeout_sec="${1:-300}"
  local start depth delayed
  start=$(date +%s)
  echo "Waiting for SQS visible+in-flight drain (delayed ignored), timeout=${timeout_sec}s ..."
  while true; do
    depth=$(queue_undelayed_depth)
    if [[ "$depth" -eq 0 ]]; then
      delayed=$(queue_delayed || echo 0)
      echo "Visible+in-flight drained (delayed≈${delayed})."
      return 0
    fi
    if (( $(date +%s) - start > timeout_sec )); then
      delayed=$(queue_delayed || echo "?")
      echo "ERROR: visible+in-flight still ${depth} (delayed≈${delayed}) after ${timeout_sec}s" >&2
      exit 1
    fi
    sleep 1
  done
}

wait_queue_drain() {
  local timeout_sec="${1:-300}"
  # Include Delayed — otherwise DelaySeconds backlogs look "empty" and drain returns early.
  local start depth delayed
  start=$(date +%s)
  echo "Waiting for SQS drain (visible+notVisible+delayed=0), timeout=${timeout_sec}s ..."
  while true; do
    depth=$(queue_depth_all)
    if [[ "$depth" -eq 0 ]]; then
      echo "Queue drained."
      return 0
    fi
    if (( $(date +%s) - start > timeout_sec )); then
      delayed=$(queue_delayed || echo "?")
      echo "ERROR: queue still has depth_all=${depth} (delayed≈${delayed}) after ${timeout_sec}s" >&2
      exit 1
    fi
    sleep 1
  done
}

# Block until ApproximateNumberOfMessagesDelayed is 0 (backlog fully released to visible/in-flight).
wait_delayed_clear() {
  local timeout_sec="${1:-120}"
  local start delayed
  start=$(date +%s)
  echo "Waiting for delayed count to reach 0, timeout=${timeout_sec}s ..."
  while true; do
    delayed=$(queue_delayed)
    if [[ "$delayed" -eq 0 ]]; then
      echo "No delayed messages remaining."
      return 0
    fi
    if (( $(date +%s) - start > timeout_sec )); then
      echo "ERROR: still delayed≈${delayed} after ${timeout_sec}s" >&2
      exit 1
    fi
    sleep 1
  done
}

# --- Metrics helpers (prefer /actuator/metrics; prometheus optional) ---

# HTTP GET without failing the script on non-2xx (prints body to stdout, status via return is awkward — use code file).
curl_body() {
  local url="$1"
  curl -sS "$url" 2>/dev/null || true
}

# Sum ingestion.events (or any counter) via Actuator JSON COUNT.
actuator_counter_count() {
  local metric="$1"
  local json count
  json=$(curl_body "${BASE_URL}/actuator/metrics/${metric}")
  count=$(printf '%s' "$json" | tr -d '\n' | sed -n 's/.*"statistic"[[:space:]]*:[[:space:]]*"COUNT".*"value"[[:space:]]*:[[:space:]]*\([0-9.][0-9.]*\).*/\1/p' | head -n1)
  if [[ -z "$count" ]]; then
    # Drill into known outcomes and sum
    local o sum=0 c
    for o in ACCEPTED DUPLICATE STALE CONFLICTING INVALID PERMANENTLY_FAILED; do
      c=$(actuator_counter_count_for_outcome "$metric" "$o")
      sum=$(awk -v a="$sum" -v b="$c" 'BEGIN{printf "%.0f", a+b}')
    done
    printf '%.0f\n' "${sum:-0}"
    return
  fi
  printf '%.0f\n' "$count"
}

actuator_counter_count_for_outcome() {
  local metric="$1"
  local outcome="$2"
  local json count
  json=$(curl_body "${BASE_URL}/actuator/metrics/${metric}?tag=outcome:${outcome}")
  count=$(printf '%s' "$json" | tr -d '\n' | sed -n 's/.*"statistic"[[:space:]]*:[[:space:]]*"COUNT".*"value"[[:space:]]*:[[:space:]]*\([0-9.][0-9.]*\).*/\1/p' | head -n1)
  printf '%.0f\n' "${count:-0}"
}

# Sum Micrometer counter samples from /actuator/prometheus when available.
# Accepts Micrometer name with dots (ingestion.events) or underscores (ingestion_events).
prometheus_counter_sum() {
  local metric="$1"
  local actuator_name="${metric//_/.}"
  local prom_name="${metric//./_}"
  local prom
  prom=$(curl -sS -o /tmp/prom-scrape.$$ -w "%{http_code}" "${BASE_URL}/actuator/prometheus" 2>/dev/null || echo "000")
  if [[ "$prom" != "200" ]]; then
    rm -f /tmp/prom-scrape.$$
    actuator_counter_count "$actuator_name"
    return
  fi
  awk -v m="${prom_name}_total" '
    $1 ~ "^"m"(\\{|$)" {
      v=$NF; sum+=v+0
    }
    END { printf "%.0f\n", sum+0 }
  ' /tmp/prom-scrape.$$
  rm -f /tmp/prom-scrape.$$
}

# Extract percentile from /actuator/metrics JSON (Micrometer VALUE + percentile tag).
# Args: metricName tagQueryString percentileKey e.g. 0.95
# Encode `{` `}` in tag values — Tomcat rejects raw braces in the request target.
actuator_percentile() {
  local metric="$1"
  local tags="$2"
  local pct="$3"
  local url="${BASE_URL}/actuator/metrics/${metric}"
  if [[ -n "$tags" ]]; then
    tags="${tags//\{/%7B}"
    tags="${tags//\}/%7D}"
    url="${url}?${tags}"
  fi
  local json
  json=$(curl_body "$url")
  printf '%s' "$json" | tr '}' '\n' | awk -v p="$pct" '
    /"percentile"/ && $0 ~ p { want=1 }
    want && /"statistic":"VALUE"/ {
      if (match($0, /"value"[[:space:]]*:[[:space:]]*[0-9.eE+-]+/)) {
        v=substr($0, RSTART, RLENGTH); sub(/.*"value"[[:space:]]*:[[:space:]]*/, "", v);
        print v+0; found=1; exit
      }
    }
    END { if (!found) print "nan" }
  '
}

# Client-side histogram_quantile over Micrometer Prometheus buckets (no quantile= summary lines).
# Args: scrape_file metric_base quantile [uri_substr] [status]
# Example: prometheus_histogram_quantile f.txt http_server_requests_seconds 0.95 "/balances/{accountId}" 200
prometheus_histogram_quantile() {
  local file="$1"
  local metric="$2"
  local quantile="$3"
  local uri="${4:-}"
  local status="${5:-}"
  if [[ ! -f "$file" ]]; then
    echo "nan"
    return
  fi
  awk -v m="$metric" -v q="$quantile" -v uri="$uri" -v st="$status" '
    function matches(line,   ok) {
      ok = 1
      if (uri != "" && index(line, "uri=\"" uri "\"") == 0) ok = 0
      if (st != "" && index(line, "status=\"" st "\"") == 0) ok = 0
      return ok
    }
    $1 ~ "^"m"_bucket\\{" {
      if (!matches($0)) next
      if (match($0, /le="[^"]+"/)) {
        le = substr($0, RSTART + 4, RLENGTH - 5)
        if (le == "+Inf") next
        buckets[++n] = le + 0
        counts[n] = $NF + 0
      }
      next
    }
    $1 ~ "^"m"_count\\{" {
      if (!matches($0)) next
      total = $NF + 0
      next
    }
    END {
      if (n < 1 || total <= 0 || q <= 0 || q >= 1) { print "nan"; exit }
      # Ensure ascending by le (Micrometer usually already is).
      for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (buckets[i] > buckets[j]) {
        t = buckets[i]; buckets[i] = buckets[j]; buckets[j] = t
        t = counts[i]; counts[i] = counts[j]; counts[j] = t
      }
      rank = q * total
      prev_count = 0
      prev_le = 0
      for (i = 1; i <= n; i++) {
        if (counts[i] >= rank) {
          if (i == 1 || counts[i] == prev_count) { printf "%.9f\n", buckets[i]; exit }
          # Linear interpolation within the bucket (Prometheus-style).
          frac = (rank - prev_count) / (counts[i] - prev_count)
          printf "%.9f\n", prev_le + (buckets[i] - prev_le) * frac
          exit
        }
        prev_count = counts[i]
        prev_le = buckets[i]
      }
      printf "%.9f\n", buckets[n]
    }
  ' "$file"
}

# Prefer summary quantile= lines; else histogram buckets for balance GETs.
prometheus_quantile() {
  local metric="$1" # e.g. http_server_requests_seconds
  local quantile="$2" # 0.95
  local scrape_file="${3:-}"
  local tmp=""
  if [[ -z "$scrape_file" || ! -f "$scrape_file" ]]; then
    tmp="/tmp/prom-q.$$"
    scrape_file="$tmp"
    local code
    code=$(curl -sS -o "$scrape_file" -w "%{http_code}" "${BASE_URL}/actuator/prometheus" 2>/dev/null || echo "000")
    if [[ "$code" != "200" ]]; then
      rm -f "$tmp"
      echo "nan"
      return
    fi
  fi
  local from_summary
  from_summary=$(awk -v m="$metric" -v q="$quantile" '
    $0 ~ "^"m"\\{" && $0 ~ "quantile=\""q"\"" {
      print $NF+0; found=1; exit
    }
    END { if (!found) print "nan" }
  ' "$scrape_file")
  if [[ "$from_summary" != "nan" ]]; then
    [[ -n "$tmp" ]] && rm -f "$tmp"
    echo "$from_summary"
    return
  fi
  local from_hist="nan"
  if [[ "$metric" == "http_server_requests_seconds" ]]; then
    from_hist=$(prometheus_histogram_quantile "$scrape_file" "$metric" "$quantile" "/balances/{accountId}" "200")
  else
    from_hist=$(prometheus_histogram_quantile "$scrape_file" "$metric" "$quantile" "" "")
  fi
  [[ -n "$tmp" ]] && rm -f "$tmp"
  echo "$from_hist"
}

iso_now() {
  date -u +"%Y-%m-%dT%H:%M:%SZ"
}

# Run SQL against the account DB.
# Order: host psql → docker exec $PG_DOCKER_CONTAINER (default account-pg) → compose service postgres.
psql_account() {
  local sql="$1"
  local out=""
  local err="${WORK_DIR:-/tmp}/psql_account.err"
  if command -v psql >/dev/null 2>&1; then
    out=$(PGPASSWORD="${DB_PASSWORD:-account}" psql \
      -h "${DB_HOST:-localhost}" -p "${DB_PORT:-5432}" \
      -U "${DB_USER:-account}" -d "${DB_NAME:-account}" \
      -v ON_ERROR_STOP=1 -tAc "$sql" 2>"$err" || true)
    if [[ -n "$out" ]]; then
      printf '%s\n' "$out"
      return 0
    fi
  fi
  if command -v docker >/dev/null 2>&1; then
    local c="${PG_DOCKER_CONTAINER:-account-pg}"
    if docker inspect "$c" >/dev/null 2>&1; then
      out=$(docker exec -i "$c" psql -U "${DB_USER:-account}" -d "${DB_NAME:-account}" \
        -v ON_ERROR_STOP=1 -tAc "$sql" 2>"$err" || true)
      if [[ -n "$out" ]]; then
        printf '%s\n' "$out"
        return 0
      fi
    fi
    local compose_file="${COMPOSE_FILE:-${REPO_ROOT}/deploy/compose/docker-compose.yml}"
    if [[ -f "$compose_file" ]]; then
      out=$(docker compose -f "$compose_file" exec -T postgres \
        psql -U account -d account -v ON_ERROR_STOP=1 -tAc "$sql" 2>"$err" || true)
      if [[ -n "$out" ]]; then
        printf '%s\n' "$out"
        return 0
      fi
    fi
  fi
  return 1
}

# Journal HTTP ingest span (receivedAt min/max via GET /internal/journal/ingest-span).
# Requires journal.read (local: JOURNAL_ALLOW_ANONYMOUS_READ=true). Falls back to caller on 403/non-200.
# Args: ISO-8601 lower bound, comma-separated account UUIDs.
# Prints: count|min_epoch|max_epoch|span_s|eps
measure_journal_ingest_span() {
  local since_iso="$1"
  local account_csv="$2"
  local curl_args=()
  local id http body eps span min_e max_e count added=0
  curl_args+=(--get --data-urlencode "since=${since_iso}")
  IFS=',' read -ra ids <<<"$account_csv"
  for id in "${ids[@]}"; do
    id="${id//[[:space:]]/}"
    [[ -z "$id" ]] && continue
    curl_args+=(--data-urlencode "accountId=${id}")
    added=1
  done
  if [[ "$added" -eq 0 ]]; then
    echo "0|nan|nan|nan|nan"
    return 1
  fi
  body=$(mktemp "${TMPDIR:-/tmp}/journal-span.XXXXXX")
  http=$(curl -sS -o "$body" -w '%{http_code}' "${curl_args[@]}" \
    "${BASE_URL}/internal/journal/ingest-span" || echo 000)
  if [[ "$http" != "200" ]]; then
    rm -f "$body"
    echo "0|nan|nan|nan|nan"
    return 1
  fi
  if command -v python >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1; then
    local py
    py=$(command -v python3 || command -v python)
    IFS='|' read -r count min_e max_e span eps <<<"$("$py" - "$body" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    d = json.load(f)
def n(v):
    return "nan" if v is None else v
print(f"{int(d.get('eventCount') or 0)}|{n(d.get('minReceivedAtEpochSeconds'))}|{n(d.get('maxReceivedAtEpochSeconds'))}|{n(d.get('spanSeconds'))}|{n(d.get('eps'))}")
PY
)"
  else
    count=$(sed -n 's/.*"eventCount"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$body" | head -n1)
    min_e=$(sed -n 's/.*"minReceivedAtEpochSeconds"[[:space:]]*:[[:space:]]*\([0-9.][0-9.]*\).*/\1/p' "$body" | head -n1)
    max_e=$(sed -n 's/.*"maxReceivedAtEpochSeconds"[[:space:]]*:[[:space:]]*\([0-9.][0-9.]*\).*/\1/p' "$body" | head -n1)
    span=$(sed -n 's/.*"spanSeconds"[[:space:]]*:[[:space:]]*\([0-9.][0-9.]*\).*/\1/p' "$body" | head -n1)
    eps=$(sed -n 's/.*"eps"[[:space:]]*:[[:space:]]*\([0-9.][0-9.]*\).*/\1/p' "$body" | head -n1)
    : "${count:=0}"
    : "${min_e:=nan}"
    : "${max_e:=nan}"
    : "${span:=nan}"
    : "${eps:=nan}"
  fi
  rm -f "$body"
  if [[ "$eps" == "null" || "$eps" == "None" || -z "$eps" ]]; then
    eps="nan"
  fi
  if [[ -z "$count" || "$count" == "0" || "$eps" == "nan" ]]; then
    echo "${count:-0}|${min_e:-nan}|${max_e:-nan}|${span:-nan}|nan"
    return 1
  fi
  echo "${count}|${min_e}|${max_e}|${span}|${eps}"
}

# Durable ingest span from processed_transaction.first_processed_at (not wall-clock drain).
# Args: ISO-8601 lower bound (timestamptz), comma-separated account UUIDs.
# Prints: count|min_epoch|max_epoch|span_s|eps  (eps=nan if span < 0.001s)
measure_db_ingest_span() {
  local since_iso="$1"
  local account_csv="$2"
  local sql accounts_sql
  accounts_sql=$(printf '%s' "$account_csv" | awk -F, '{
    for (i=1;i<=NF;i++) {
      gsub(/^[ \t]+|[ \t]+$/, "", $i)
      if ($i == "") continue
      if (n++) printf ","
      printf "\047%s\047::uuid", $i
    }
  }')
  if [[ -z "$accounts_sql" ]]; then
    echo "0|nan|nan|nan|nan"
    return 1
  fi
  sql=$(cat <<EOF
SELECT CONCAT_WS('|',
  COUNT(*)::text,
  COALESCE(EXTRACT(EPOCH FROM MIN(first_processed_at))::text, 'nan'),
  COALESCE(EXTRACT(EPOCH FROM MAX(first_processed_at))::text, 'nan'),
  COALESCE(EXTRACT(EPOCH FROM (MAX(first_processed_at) - MIN(first_processed_at)))::text, 'nan')
)
FROM processed_transaction
WHERE first_processed_at > TIMESTAMPTZ '${since_iso}'
  AND account_id IN (${accounts_sql});
EOF
)
  local row count min_e max_e span eps
  row=$(psql_account "$sql" | tr -d '[:space:]' || true)
  if [[ -z "$row" || "$row" == "0|"* ]]; then
    echo "0|nan|nan|nan|nan"
    return 1
  fi
  IFS='|' read -r count min_e max_e span <<<"$row"
  eps=$(awk -v c="$count" -v s="$span" 'BEGIN{
    if (s+0 < 0.001) { print "nan"; exit }
    printf "%.1f", c/s
  }')
  echo "${count}|${min_e}|${max_e}|${span}|${eps}"
}
