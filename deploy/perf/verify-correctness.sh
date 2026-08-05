#!/usr/bin/env bash
# Verify final account correctness (HTTP 200 + expected amount) and durable processing.
# Optional: Postgres row counts via docker compose exec when COMPOSE_PROJECT is set.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl

: "${WORK_DIR:?WORK_DIR is required}"
# shellcheck disable=SC1090
source "${WORK_DIR}/workload.meta"

# Allow caller override after meta load (e.g. seed-only expected file).
EXPECTED_FILE="${EXPECTED_FILE_OVERRIDE:-${EXPECTED_FILE:-${WORK_DIR}/expected_balances.csv}}"
: "${VERIFY_QUEUE_MODE:=all}" # all | undelayed (seed while main DelaySeconds backlog is hidden)
published=$(cat "${WORK_DIR}/published_count.txt" 2>/dev/null || echo "${TOTAL_EVENTS}")
if [[ -n "${VERIFY_MIN_PUBLISHED:-}" ]]; then
  published="${VERIFY_MIN_PUBLISHED}"
fi

fail=0
checked=0
ok=0

echo "Verifying balances against ${EXPECTED_FILE} ..."
while IFS=, read -r acct expected _idx; do
  [[ -z "$acct" ]] && continue
  checked=$((checked + 1))
  resp=$(curl -sS -w "\n%{http_code}" "${BASE_URL}/balances/${acct}" || true)
  code=$(printf '%s' "$resp" | tail -n1)
  body=$(printf '%s' "$resp" | sed '$d')
  if [[ "$code" != "200" ]]; then
    echo "FAIL account=${acct} status=${code}" >&2
    fail=1
    continue
  fi
  amount=$(printf '%s' "$body" | sed -n 's/.*"amount"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' | head -n1)
  # Normalize decimals
  exp_n=$(awk -v a="$expected" 'BEGIN{printf "%.2f", a+0}')
  got_n=$(awk -v a="$amount" 'BEGIN{printf "%.2f", a+0}')
  if [[ "$exp_n" != "$got_n" ]]; then
    echo "FAIL account=${acct} expected=${exp_n} got=${got_n}" >&2
    fail=1
    continue
  fi
  ok=$((ok + 1))
done < "${EXPECTED_FILE}"

if [[ "$VERIFY_QUEUE_MODE" == "undelayed" ]]; then
  depth=$(queue_undelayed_depth)
  delayed=$(queue_delayed || echo "?")
  echo "sqs_undelayed_depth_after=${depth} delayed≈${delayed}"
else
  depth=$(queue_depth_all)
  echo "sqs_depth_after=${depth}"
fi
if [[ "$depth" != "0" ]]; then
  echo "FAIL queue not drained depth=${depth} mode=${VERIFY_QUEUE_MODE}" >&2
  fail=1
fi

ingest_total=$(prometheus_counter_sum "ingestion.events")
echo "ingestion_events_total=${ingest_total} published=${published}"

# Outcome breakdown helps diagnose DUPLICATE/INVALID floods that still drain the queue.
accepted=$(actuator_counter_count_for_outcome "ingestion.events" "ACCEPTED")
duplicate=$(actuator_counter_count_for_outcome "ingestion.events" "DUPLICATE")
invalid=$(actuator_counter_count_for_outcome "ingestion.events" "INVALID")
stale=$(actuator_counter_count_for_outcome "ingestion.events" "STALE")
echo "ingestion_outcomes accepted=${accepted} stale=${stale} duplicate=${duplicate} invalid=${invalid}"
if [[ "$duplicate" -gt 0 && "$accepted" -eq 0 && "$ok" -eq 0 ]]; then
  echo "FAIL all ingest outcomes are non-ACCEPTED (duplicate=${duplicate}) — likely reused transaction IDs; check RUN_HASH in workload.meta" >&2
  fail=1
fi

# Durability: every published event should eventually produce a journal/metric outcome.
# Allow small race if metrics scrape lags; require at least published count.
if [[ "$ingest_total" -lt "$published" ]]; then
  echo "FAIL ingestion_events_total (${ingest_total}) < published (${published})" >&2
  fail=1
fi

# Optional DB checks via docker
if [[ -n "${COMPOSE_FILE:-}" ]] || [[ -f "${REPO_ROOT}/deploy/compose/docker-compose.yml" ]]; then
  compose_file="${COMPOSE_FILE:-${REPO_ROOT}/deploy/compose/docker-compose.yml}"
  if command -v docker >/dev/null 2>&1; then
    processed=$(docker compose -f "$compose_file" exec -T postgres \
      psql -U account -d account -tAc "SELECT COUNT(*) FROM processed_transaction;" 2>/dev/null | tr -d '[:space:]' || true)
    snapshots=$(docker compose -f "$compose_file" exec -T postgres \
      psql -U account -d account -tAc "SELECT COUNT(*) FROM account_balance_snapshot;" 2>/dev/null | tr -d '[:space:]' || true)
    journal=$(docker compose -f "$compose_file" exec -T postgres \
      psql -U account -d account -tAc "SELECT COUNT(*) FROM journal_processing_record;" 2>/dev/null | tr -d '[:space:]' || true)
    if [[ -n "$processed" ]]; then
      echo "db_processed_transaction=${processed} db_snapshots=${snapshots} db_journal=${journal}"
      if [[ "$processed" -lt "$published" ]]; then
        echo "FAIL processed_transaction (${processed}) < published (${published})" >&2
        fail=1
      fi
      if [[ "$snapshots" -lt "$ACCOUNT_COUNT" ]]; then
        echo "FAIL snapshots (${snapshots}) < ACCOUNT_COUNT (${ACCOUNT_COUNT})" >&2
        fail=1
      fi
      if [[ -n "$journal" && "$journal" -lt "$published" ]]; then
        echo "FAIL journal_processing_record (${journal}) < published (${published})" >&2
        fail=1
      fi
    else
      echo "WARN: could not query Postgres via docker compose (skipped DB durability counts)"
    fi
  fi
fi

{
  echo "checked=${checked}"
  echo "ok=${ok}"
  echo "fail=${fail}"
  echo "published=${published}"
  echo "ingestion_events_total=${ingest_total}"
  echo "sqs_depth=${depth}"
} | tee "${WORK_DIR}/correctness.txt"

if [[ "$fail" -ne 0 ]]; then
  echo "Correctness verification FAILED (${ok}/${checked} balances ok)" >&2
  exit 1
fi
echo "Correctness verification PASSED (${ok}/${checked} balances ok, queue drained, durable counts ok)"
