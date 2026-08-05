#!/usr/bin/env bash
# End-to-end performance validation against design-doc §7 / SC-003:
#   - Ingestion ≥ 2_000 events/s (diagnostic locally; prove only on target-like EKS/RDS)
#   - Query p95/p99 while ingestion is still active (k6 overlaps consume window)
#   - Durable ingest span from journal ingest-span (receivedAt) or DB first_processed_at
#   - Single-burst publish (seed immediate + main DelaySeconds) via async java publisher
#   - Final balance correctness + ACK/durability checks
#
# Prerequisites: account-service + Postgres + LocalStack (or AWS) reachable;
#   aws CLI, curl, bash; k6 for the query phase.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd aws curl
if ! command -v k6 >/dev/null 2>&1; then
  echo "ERROR: k6 is required for the concurrent query phase (https://k6.io)" >&2
  exit 1
fi

: "${ACCOUNT_COUNT:=50}"
: "${EVENTS_PER_ACCOUNT:=40}"
: "${PUBLISH_WORKERS:=32}"
: "${PUBLISH_MAX_INFLIGHT:=64}"
: "${K6_VUS:=50}"
: "${K6_DURATION:=2m}"
: "${DRAIN_TIMEOUT_SEC:=600}"
: "${INGEST_TARGET_EPS:=2000}"
: "${QUERY_P95_MAX_MS:=100}"
: "${QUERY_P99_MAX_MS:=250}"
# SQS DelaySeconds for the main load so the queue fills before the consumer can see messages.
# Consume-only EPS = events / time from full backlog visible → drain (excludes publisher speed).
: "${INGEST_BACKLOG_DELAY_SECONDS:=30}"

if [[ "$EVENTS_PER_ACCOUNT" -lt 2 ]]; then
  echo "ERROR: EVENTS_PER_ACCOUNT must be >= 2 (1 seed + remaining main load)" >&2
  exit 1
fi
if [[ "${INGEST_BACKLOG_DELAY_SECONDS}" -lt 5 || "${INGEST_BACKLOG_DELAY_SECONDS}" -gt 900 ]]; then
  echo "ERROR: INGEST_BACKLOG_DELAY_SECONDS must be 5..900 (got ${INGEST_BACKLOG_DELAY_SECONDS})" >&2
  exit 1
fi

RESULTS_DIR="${PERF_ROOT}/results"
mkdir -p "${RESULTS_DIR}"
RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)"
export WORK_DIR="${PERF_ROOT}/work/${RUN_ID}"
RESULT_FILE="${RESULTS_DIR}/run-${RUN_ID}.md"

echo "=== Perf benchmark ${RUN_ID} ==="
echo "BASE_URL=${BASE_URL}"
echo "SQS_QUEUE_URL=${SQS_QUEUE_URL}"

wait_ready

echo "--- Purge leftover SQS messages ---"
purge_perf_queues

echo "--- Generate workload ---"
ACCOUNT_COUNT="${ACCOUNT_COUNT}" EVENTS_PER_ACCOUNT="${EVENTS_PER_ACCOUNT}" \
  WORK_DIR="${WORK_DIR}" RUN_ID="${RUN_ID}" \
  bash "${SCRIPT_DIR}/generate-workload.sh"
# shellcheck disable=SC1090
source "${WORK_DIR}/workload.meta"
ACCOUNT_IDS=$(cat "${WORK_DIR}/account_ids.txt")

echo "--- Publish all events in one burst (seed idx=1 immediate, idx=2..${EVENTS_PER_ACCOUNT} DelaySeconds=${INGEST_BACKLOG_DELAY_SECONDS}) ---"
WORK_DIR="${WORK_DIR}" START_INDEX=1 END_INDEX="${EVENTS_PER_ACCOUNT}" \
  PUBLISH_WORKERS="${PUBLISH_WORKERS}" PUBLISH_MAX_INFLIGHT="${PUBLISH_MAX_INFLIGHT}" \
  DELAY_SECONDS="${INGEST_BACKLOG_DELAY_SECONDS}" IMMEDIATE_THROUGH_INDEX=1 \
  bash "${SCRIPT_DIR}/publish-ingest-load.sh"
# DelaySeconds is relative to each SendMessageBatch completion time.
publish_end_s=$(date +%s)
publish_count=$(cat "${WORK_DIR}/published_count.txt" 2>/dev/null || echo 0)
publish_elapsed_sdk=$(cat "${WORK_DIR}/publish_elapsed_s.txt" 2>/dev/null || echo "1")
publish_rate=$(cat "${WORK_DIR}/publish_rate.txt" 2>/dev/null || echo "?")
delayed_after_publish=$(queue_delayed || echo "?")
depth_all_after_publish=$(queue_depth_all || echo "?")
echo "Burst staged: published=${publish_count} publish_sdk_elapsed_s=${publish_elapsed_sdk} publish_rate_msg_s=${publish_rate} delayed≈${delayed_after_publish} depth_all≈${depth_all_after_publish}"

echo "--- Wait for seed (visible+in-flight) while main load stays delayed ---"
wait_undelayed_drain 180

# Temporary expected file for seed-only balances
seed_expected="${WORK_DIR}/expected_balances_seed.csv"
: > "${seed_expected}"
while IFS=, read -r acct _rest; do
  [[ -z "$acct" ]] && continue
  printf "%s,1.00,1\n" "$acct" >> "${seed_expected}"
done < "${WORK_DIR}/accounts.csv"
EXPECTED_FILE_OVERRIDE="${seed_expected}" VERIFY_QUEUE_MODE=undelayed \
  VERIFY_MIN_PUBLISHED="${ACCOUNT_COUNT}" WORK_DIR="${WORK_DIR}" \
  bash "${SCRIPT_DIR}/verify-correctness.sh"

echo "--- Metrics T0 / T0b (seed consumed, main still delayed) ---"
WORK_DIR="${WORK_DIR}" LABEL=t0 bash "${SCRIPT_DIR}/collect-server-metrics.sh"
t0_seed_events=$(grep '^ingestion_events_total=' "${WORK_DIR}/metrics-t0.txt" | cut -d= -f2)
# Lower bound for ingest span: after seed claims, before main-load first_processed_at / receivedAt.
db_span_since_iso=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
echo "db_span_since_iso=${db_span_since_iso}"
WORK_DIR="${WORK_DIR}" LABEL=t0b bash "${SCRIPT_DIR}/collect-server-metrics.sh"
t0_events=$(grep '^ingestion_events_total=' "${WORK_DIR}/metrics-t0b.txt" | cut -d= -f2)
if [[ "$t0_events" -ne "$t0_seed_events" ]]; then
  echo "WARN: ingestion.events moved after seed drain (${t0_seed_events} → ${t0_events}); DelaySeconds may be too short vs publish wall time" >&2
fi

# Wait until the *last* send's delay elapses so the full backlog is visible together.
# (Do not use pre-mvn wall clock — DelaySeconds starts at each SendMessage time.)
all_visible_at=$((publish_end_s + INGEST_BACKLOG_DELAY_SECONDS))
publish_elapsed_ceil=$(awk -v s="$publish_elapsed_sdk" 'BEGIN{printf "%d", (s+0.999)}')
[[ "$publish_elapsed_ceil" -lt 1 ]] && publish_elapsed_ceil=1
# First main-load messages become visible ~publish_start+DelaySeconds, before all_visible_at.
first_visible_at=$((all_visible_at - publish_elapsed_ceil))
wait_until_epoch "$all_visible_at"
wait_delayed_clear 120

echo "--- SC-003: k6 query load overlapping consume window (db_span_since=${db_span_since_iso}) ---"
k6_log="${WORK_DIR}/k6.log"
k6_status=0
(
  BASE_URL="${BASE_URL}" ACCOUNT_IDS="${ACCOUNT_IDS}" VUS="${K6_VUS}" DURATION="${K6_DURATION}" \
    k6 run "${SCRIPT_DIR}/k6-balance-query.js"
) >"${k6_log}" 2>&1 &
k6_pid=$!

echo "--- Consume window (first visible → drain, k6 running; wall starts at first_visible_at=${first_visible_at}) ---"
wait_queue_drain "${DRAIN_TIMEOUT_SEC}"
consume_end_s=$(date +%s)
ingest_elapsed=$((consume_end_s - first_visible_at))
[[ "$ingest_elapsed" -lt 1 ]] && ingest_elapsed=1

echo "--- Metrics T1 (drain complete; k6 overlapped ingest — SC-003 query scrape) ---"
WORK_DIR="${WORK_DIR}" LABEL=t1 bash "${SCRIPT_DIR}/collect-server-metrics.sh"
t1_events=$(grep '^ingestion_events_total=' "${WORK_DIR}/metrics-t1.txt" | cut -d= -f2)
t1_depth=$(grep '^sqs_depth=' "${WORK_DIR}/metrics-t1.txt" | cut -d= -f2)
if [[ "${t1_depth}" != "0" ]]; then
  echo "ERROR: T1 still reports sqs_depth=${t1_depth} after drain" >&2
  kill "$k6_pid" >/dev/null 2>&1 || true
  exit 1
fi

delta_events=$((t1_events - t0_events))
wall_eps=$(awk -v d="$delta_events" -v t="$ingest_elapsed" 'BEGIN{printf "%.1f", d/t}')

journal_span_row=$(measure_journal_ingest_span "$db_span_since_iso" "$ACCOUNT_IDS" || echo "0|nan|nan|nan|nan")
IFS='|' read -r journal_count journal_min_e journal_max_e journal_span_s journal_eps <<<"$journal_span_row"
echo "${journal_span_row}" > "${WORK_DIR}/journal_ingest_span.txt"
db_span_row=$(measure_db_ingest_span "$db_span_since_iso" "$ACCOUNT_IDS" || echo "0|nan|nan|nan|nan")
IFS='|' read -r db_count db_min_e db_max_e db_span_s db_eps <<<"$db_span_row"
echo "${db_span_row}" > "${WORK_DIR}/db_ingest_span.txt"
echo "Wall consume ingest_eps=${wall_eps} (delta_events=${delta_events} / wall_s=${ingest_elapsed} from first_visible)"
echo "Journal receivedAt span: count=${journal_count} span_s=${journal_span_s} eps=${journal_eps} (since ${db_span_since_iso})"
echo "DB first_processed_at span: count=${db_count} span_s=${db_span_s} eps=${db_eps} (since ${db_span_since_iso})"
echo "Publisher was ${publish_rate} msg/s over ${publish_elapsed_sdk}s (not the ingest gate)"

# Prefer journal HTTP span, then durable DB span, else wall-clock from first visibility.
eps="$wall_eps"
eps_source="wall_first_visible"
if [[ "$journal_eps" != "nan" && -n "$journal_eps" ]] && awk -v s="$journal_span_s" 'BEGIN{ exit !(s+0 >= 1.0) }'; then
  eps="$journal_eps"
  eps_source="journal_received_at"
elif [[ "$db_eps" != "nan" && -n "$db_eps" ]] && awk -v s="$db_span_s" 'BEGIN{ exit !(s+0 >= 1.0) }'; then
  eps="$db_eps"
  eps_source="db_first_processed_at"
fi

p95_s=$(grep '^http_server_requests_p95_seconds=' "${WORK_DIR}/metrics-t1.txt" | cut -d= -f2)
p99_s=$(grep '^http_server_requests_p99_seconds=' "${WORK_DIR}/metrics-t1.txt" | cut -d= -f2)
t0_p95_s=$(grep '^http_server_requests_p95_seconds=' "${WORK_DIR}/metrics-t0.txt" | cut -d= -f2 || echo nan)
if [[ "$t0_p95_s" == "$p95_s" && "$p95_s" != "nan" ]]; then
  echo "WARN: T0 and T1 http p95 are identical (${p95_s}s) — cumulative histogram likely predates this run; restart the JVM for a clean SC-003 sample" >&2
fi
p95_ms=$(awk -v s="$p95_s" 'BEGIN{ if (s=="nan"||s=="") {print "nan"} else printf "%.2f", s*1000 }')
p99_ms=$(awk -v s="$p99_s" 'BEGIN{ if (s=="nan"||s=="") {print "nan"} else printf "%.2f", s*1000 }')

echo "--- Wait for k6 to finish (post-drain traffic is observational, not SC-003) ---"
wait "$k6_pid" || k6_status=$?

echo "--- Metrics T2 (post k6 — cumulative histogram, not the query gate) ---"
WORK_DIR="${WORK_DIR}" LABEL=t2 bash "${SCRIPT_DIR}/collect-server-metrics.sh"

echo "--- Correctness (final balances + durability) ---"
echo "${TOTAL_EVENTS}" > "${WORK_DIR}/published_count.txt"
WORK_DIR="${WORK_DIR}" bash "${SCRIPT_DIR}/verify-correctness.sh"
correctness_status=$?
echo "main_load_published=$((TOTAL_EVENTS - ACCOUNT_COUNT))" >> "${WORK_DIR}/correctness.txt"
echo "journal_ingest_span=${journal_span_row}" >> "${WORK_DIR}/correctness.txt"
echo "db_ingest_span=${db_span_row}" >> "${WORK_DIR}/correctness.txt"
echo "ingest_eps_source=${eps_source}" >> "${WORK_DIR}/correctness.txt"

ingest_pass=0
awk -v eps="$eps" -v tgt="$INGEST_TARGET_EPS" 'BEGIN{ exit !(eps+0 >= tgt+0) }' && ingest_pass=1

query_pass=0
if [[ "$p95_ms" != "nan" && "$p99_ms" != "nan" ]]; then
  awk -v p95="$p95_ms" -v p99="$p99_ms" -v m95="$QUERY_P95_MAX_MS" -v m99="$QUERY_P99_MAX_MS" \
    'BEGIN{ exit !(p95+0 <= m95+0 && p99+0 <= m99+0) }' && query_pass=1
fi

k6_pass=0
[[ "$k6_status" -eq 0 ]] && k6_pass=1

overall=FAIL
if [[ "$ingest_pass" -eq 1 && "$query_pass" -eq 1 && "$k6_pass" -eq 1 && "$correctness_status" -eq 0 ]]; then
  overall=PASS
fi

published=$(cat "${WORK_DIR}/published_count.txt")

cat > "${RESULT_FILE}" <<EOF
# Performance run ${RUN_ID}

| Field | Value |
|-------|-------|
| Overall | **${overall}** |
| Collected at (UTC) | $(iso_now) |
| BASE_URL | ${BASE_URL} |
| Accounts | ${ACCOUNT_COUNT} |
| Events / account | ${EVENTS_PER_ACCOUNT} (1 seed + $((EVENTS_PER_ACCOUNT - 1)) main) |
| Published (total) | ${published} |
| Publisher SDK window s / rate | ${publish_elapsed_sdk}s / ${publish_rate} msg/s (not gated) |
| Backlog DelaySeconds | ${INGEST_BACKLOG_DELAY_SECONDS} |
| Wall consume window (first visible→drain) s | ${ingest_elapsed} |
| \`ingestion.events\` Δ (T1−T0b) | ${delta_events} |
| Wall ingest rate (events/s) | ${wall_eps} |
| Journal \`receivedAt\` count / span_s / eps | ${journal_count} / ${journal_span_s} / ${journal_eps} |
| DB \`first_processed_at\` count / span_s / eps | ${db_count} / ${db_span_s} / ${db_eps} |
| **Ingest rate used for gate (events/s)** | **${eps}** (source=${eps_source}; target ≥ ${INGEST_TARGET_EPS}) |
| Ingest gate | $([[ "$ingest_pass" -eq 1 ]] && echo PASS || echo FAIL) |
| **Server http.server.requests p95 @T1 (ms)** | **${p95_ms}** (target ≤ ${QUERY_P95_MAX_MS}; k6 overlapped ingest) |
| **Server http.server.requests p99 @T1 (ms)** | **${p99_ms}** (target ≤ ${QUERY_P99_MAX_MS}; k6 overlapped ingest) |
| Query SLO gate (SC-003 scrape = T1) | $([[ "$query_pass" -eq 1 ]] && echo PASS || echo FAIL) |
| k6 (started before drain) | $([[ "$k6_pass" -eq 1 ]] && echo PASS || echo FAIL) (log: \`deploy/perf/work/${RUN_ID}/k6.log\`) |
| Correctness + durability | $([[ "$correctness_status" -eq 0 ]] && echo PASS || echo FAIL) |

## What this run is (and is not)

1. Query checks require **HTTP 200** + balance body (404 fails).
2. SC-003 query scrape is **T1** (immediately after drain) while k6 was running during consume. Histograms are **cumulative** (include seed traffic); T2 after k6 finishes is observational only.
3. Ingest rate prefers journal \`GET /internal/journal/ingest-span\` (\`receivedAt\` min/max), then DB \`first_processed_at\`, then wall from first visibility. Publisher msg/s is not the gate. Journal span needs \`JOURNAL_ALLOW_ANONYMOUS_READ=true\` locally (still deny-by-default in production).
4. A short local window (few seconds, one process, LocalStack) is a **diagnostic observation**, not production sizing evidence. Do **not** multiply EPS × replica count.
5. Proving ≥2k EPS + SC-003 requires: multi-pod EKS/RDS, simultaneous ingest+query, **10–15 min** sustained load, spec account/event distribution, plus backlog-drain / DB saturation / failure-recovery drills.

## Artifacts

- Work dir: \`deploy/perf/work/${RUN_ID}/\`
- Metrics: \`metrics-t0.txt\`, \`metrics-t0b.txt\`, \`metrics-t1.txt\`, \`metrics-t2.txt\`, prometheus scrapes
- Journal span: \`journal_ingest_span.txt\`
- DB span: \`db_ingest_span.txt\`
- Correctness: \`correctness.txt\`
- k6: \`k6.log\`

## Notes

- LocalStack / laptop results are **diagnostics**.
- If journal span is \`nan\` (403 deny-all), set \`JOURNAL_ALLOW_ANONYMOUS_READ=true\` and restart, or rely on DB/\`account-pg\` docker exec / host \`psql\`.
- Keep \`INGEST_BACKLOG_DELAY_SECONDS\` above SDK publish duration so staging finishes before visibility.
EOF

echo
echo "=== RESULT ${overall} — wrote ${RESULT_FILE} ==="
echo "ingest_eps=${eps} source=${eps_source} wall_eps=${wall_eps} journal_eps=${journal_eps} db_eps=${db_eps} publish_rate_msg_s=${publish_rate} server_p95_ms=${p95_ms} server_p99_ms=${p99_ms}"
[[ "$overall" == "PASS" ]]
