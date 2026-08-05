#!/usr/bin/env bash
# Collect server-side latency and ingestion counters from Actuator (Prometheus when available).
# Writes metrics snapshot files under WORK_DIR.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

require_cmd curl

: "${WORK_DIR:?WORK_DIR is required}"
: "${LABEL:=snapshot}"

out="${WORK_DIR}/metrics-${LABEL}.txt"
prom="${WORK_DIR}/prometheus-${LABEL}.txt"

prom_code=$(curl -sS -o "${prom}" -w "%{http_code}" "${BASE_URL}/actuator/prometheus" 2>/dev/null || echo "000")
if [[ "$prom_code" != "200" ]]; then
  echo "WARN: /actuator/prometheus returned ${prom_code} — using /actuator/metrics fallback (rebuild app with micrometer-registry-prometheus for scrape)" >&2
  echo "# prometheus unavailable status=${prom_code}" > "${prom}"
fi

ingest_total=$(actuator_counter_count "ingestion.events")

# Prefer histogram buckets from the scrape we just wrote (Micrometer no longer emits quantile= by default).
p95=$(prometheus_quantile "http_server_requests_seconds" "0.95" "${prom}")
p99=$(prometheus_quantile "http_server_requests_seconds" "0.99" "${prom}")
proc_p95=$(prometheus_quantile "ingestion_processing_seconds" "0.95" "${prom}")

if [[ "$p95" == "nan" ]]; then
  p95=$(actuator_percentile "http.server.requests" "tag=uri:/balances/{accountId}&tag=status:200&tag=method:GET" "0.95")
fi
if [[ "$p99" == "nan" ]]; then
  p99=$(actuator_percentile "http.server.requests" "tag=uri:/balances/{accountId}&tag=status:200&tag=method:GET" "0.99")
fi
if [[ "$proc_p95" == "nan" ]]; then
  proc_p95=$(actuator_percentile "ingestion.processing" "" "0.95")
fi

depth=$(queue_depth_all || echo "unknown")

{
  echo "label=${LABEL}"
  echo "collected_at=$(iso_now)"
  echo "ingestion_events_total=${ingest_total}"
  echo "http_server_requests_p95_seconds=${p95}"
  echo "http_server_requests_p99_seconds=${p99}"
  echo "ingestion_processing_p95_seconds=${proc_p95}"
  echo "sqs_depth=${depth}"
  echo "prometheus_http_status=${prom_code}"
} | tee "${out}"
