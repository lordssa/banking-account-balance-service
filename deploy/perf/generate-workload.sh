#!/usr/bin/env bash
# Generate deterministic-within-run accounts + expected final balances.
# Account IDs are unique per RUN_ID so re-runs do not collide with leftover snapshots.
# Writes into WORK_DIR (default: deploy/perf/work/<timestamp>).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

: "${ACCOUNT_COUNT:=50}"
: "${EVENTS_PER_ACCOUNT:=40}"
: "${RUN_ID:=$(date -u +%Y%m%dT%H%M%SZ)}"
: "${WORK_DIR:=${PERF_ROOT}/work/${RUN_ID}}"

mkdir -p "${WORK_DIR}"

ACCOUNTS_FILE="${WORK_DIR}/accounts.csv"
EXPECTED_FILE="${WORK_DIR}/expected_balances.csv"
META_FILE="${WORK_DIR}/workload.meta"

# 8-hex salt from RUN_ID so each benchmark run gets fresh account UUIDs.
if command -v md5sum >/dev/null 2>&1; then
  RUN_HASH=$(printf '%s' "$RUN_ID" | md5sum | awk '{print substr($1,1,8)}')
elif command -v md5 >/dev/null 2>&1; then
  RUN_HASH=$(printf '%s' "$RUN_ID" | md5 | awk '{print substr($1,1,8)}')
else
  RUN_HASH=$(printf '%s' "$RUN_ID" | cksum | awk '{printf "%08x", $1}')
fi

# Valid UUID shape 8-4-4-4-12. Split 8-hex RUN_HASH across two 4-hex groups
# (a single 8-hex group breaks UUID.fromString and the consumer journals INVALID).
# account i -> 00000000-<h0:4>-<h4:4>-8xxx-<12 hex of i>
# owner   i -> 00000000-<h0:4>-<h4:4>-9xxx-<12 hex of i>
det_uuid() {
  local role_nibble="$1" n="$2"
  printf '00000000-%s-%s-%s000-%012x' "${RUN_HASH:0:4}" "${RUN_HASH:4:4}" "$role_nibble" "$n"
}

# Wall-clock micros so new events are always strictly newer than prior runs' snapshots
# if someone reuses accounts, and so seed/main ordering stays consistent within the run.
BASE_MICROS=$(($(date +%s) * 1000000))

: > "${ACCOUNTS_FILE}"
: > "${EXPECTED_FILE}"

i=1
while [[ $i -le $ACCOUNT_COUNT ]]; do
  acct=$(det_uuid '8' "$i")
  owner=$(det_uuid '9' "$i")
  echo "${acct},${owner}" >> "${ACCOUNTS_FILE}"
  printf "%s,%.2f,%s\n" "$acct" "${EVENTS_PER_ACCOUNT}" "${EVENTS_PER_ACCOUNT}" >> "${EXPECTED_FILE}"
  i=$((i + 1))
done

TOTAL_EVENTS=$((ACCOUNT_COUNT * EVENTS_PER_ACCOUNT))
cat > "${META_FILE}" <<EOF
ACCOUNT_COUNT=${ACCOUNT_COUNT}
EVENTS_PER_ACCOUNT=${EVENTS_PER_ACCOUNT}
TOTAL_EVENTS=${TOTAL_EVENTS}
BASE_MICROS=${BASE_MICROS}
RUN_ID=${RUN_ID}
RUN_HASH=${RUN_HASH}
ACCOUNTS_FILE=${ACCOUNTS_FILE}
EXPECTED_FILE=${EXPECTED_FILE}
GENERATED_AT=$(iso_now)
EOF

awk -F, '{printf "%s%s", (NR>1?",":""), $1}' "${ACCOUNTS_FILE}" > "${WORK_DIR}/account_ids.txt"
echo >> "${WORK_DIR}/account_ids.txt"

echo "Workload written to ${WORK_DIR}"
echo "  run_id=${RUN_ID} run_hash=${RUN_HASH} base_micros=${BASE_MICROS}"
echo "  accounts=${ACCOUNT_COUNT} events_per_account=${EVENTS_PER_ACCOUNT} total=${TOTAL_EVENTS}"
echo "WORK_DIR=${WORK_DIR}"
