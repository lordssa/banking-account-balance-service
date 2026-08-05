#!/usr/bin/env bash
# Publish deterministic financial events to SQS.
# Prefers in-process AWS SDK (SqsLoadPublisher) — Windows aws.exe per batch is ~2 msg/s.
# Fallback: AWS CLI batches (slow on Windows / OneDrive).
#
# Requires WORK_DIR from generate-workload.sh (accounts.csv + workload.meta).
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${SCRIPT_DIR}/lib.sh"

: "${WORK_DIR:?WORK_DIR is required (run generate-workload.sh first)}"
: "${PUBLISH_WORKERS:=32}"
: "${PUBLISH_MAX_INFLIGHT:=64}"
: "${CORRELATION_ATTR:=eventCorrelationId}"
: "${START_INDEX:=1}"
: "${END_INDEX:=}"
: "${PUBLISH_ENGINE:=auto}" # auto | sdk | awscli
: "${DELAY_SECONDS:=0}"
: "${IMMEDIATE_THROUGH_INDEX:=0}"

# shellcheck disable=SC1090
source "${WORK_DIR}/workload.meta"

ACCOUNTS_FILE="${ACCOUNTS_FILE:-${WORK_DIR}/accounts.csv}"
BASE_MICROS="${BASE_MICROS:-1700000000000000}"
END_INDEX="${END_INDEX:-${EVENTS_PER_ACCOUNT}}"

if [[ ! "${RUN_HASH:-}" =~ ^[0-9a-fA-F]{8}$ ]]; then
  echo "ERROR: workload.meta RUN_HASH must be 8 hex chars (got '${RUN_HASH:-}')" >&2
  exit 1
fi

if [[ "$START_INDEX" -lt 1 || "$END_INDEX" -lt "$START_INDEX" ]]; then
  echo "ERROR: invalid START_INDEX=${START_INDEX} END_INDEX=${END_INDEX}" >&2
  exit 1
fi

if [[ "${DELAY_SECONDS}" -lt 0 || "${DELAY_SECONDS}" -gt 900 ]]; then
  echo "ERROR: DELAY_SECONDS must be 0..900 (got ${DELAY_SECONDS})" >&2
  exit 1
fi

export WORK_DIR SQS_QUEUE_URL AWS_ENDPOINT_OVERRIDE AWS_REGION AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY
export START_INDEX END_INDEX PUBLISH_WORKERS PUBLISH_MAX_INFLIGHT CORRELATION_ATTR DELAY_SECONDS IMMEDIATE_THROUGH_INDEX

publisher_classpath_file() {
  printf '%s' "${REPO_ROOT}/target/perf-publisher.classpath"
}

ensure_publisher_classpath() {
  local cp_file src_file class_file pom_file
  cp_file=$(publisher_classpath_file)
  src_file="${REPO_ROOT}/src/test/java/com/itau/account/perf/SqsLoadPublisher.java"
  class_file="${REPO_ROOT}/target/test-classes/com/itau/account/perf/SqsLoadPublisher.class"
  pom_file="${REPO_ROOT}/pom.xml"
  if [[ -f "$cp_file" && -f "$class_file" && "$class_file" -nt "$src_file" && "$cp_file" -nt "$pom_file" ]]; then
    return 0
  fi
  echo "Building publisher classpath (test-compile + dependency:build-classpath) ..."
  (
    cd "${REPO_ROOT}"
    ./mvnw -q -DskipTests test-compile dependency:build-classpath \
      -DincludeScope=test \
      -Dmdep.outputFile="${cp_file}"
  )
}

java_work_dir() {
  local work_for_java="$WORK_DIR"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$WORK_DIR"
    return 0
  fi
  if [[ "$WORK_DIR" =~ ^/([a-zA-Z])/(.*)$ ]]; then
    local drive
    drive=$(printf '%s' "${BASH_REMATCH[1]}" | tr '[:lower:]' '[:upper:]')
    printf '%s:/%s' "$drive" "${BASH_REMATCH[2]}"
    return 0
  fi
  printf '%s' "$work_for_java"
}

publish_with_sdk() {
  echo "Using AWS SDK async publisher (com.itau.account.perf.SqsLoadPublisher)"
  local work_for_java cp_file java_cp
  work_for_java=$(java_work_dir)
  ensure_publisher_classpath
  cp_file=$(publisher_classpath_file)
  if [[ ! -f "$cp_file" ]]; then
    return 1
  fi
  if command -v cygpath >/dev/null 2>&1; then
    java_cp="$(cygpath -w "${REPO_ROOT}/target/test-classes");$(cygpath -w "${REPO_ROOT}/target/classes");$(cat "$cp_file")"
  else
    java_cp="${REPO_ROOT}/target/test-classes:${REPO_ROOT}/target/classes:$(cat "$cp_file")"
  fi
  (
    cd "${REPO_ROOT}"
    MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
      WORK_DIR="${work_for_java}" java -cp "${java_cp}" com.itau.account.perf.SqsLoadPublisher
  )
}

json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

# Batch files outside OneDrive — LOCALAPPDATA on Windows, /tmp elsewhere.
batch_root() {
  if [[ -n "${LOCALAPPDATA:-}" ]]; then
    if command -v cygpath >/dev/null 2>&1; then
      cygpath -u "${LOCALAPPDATA}/account-service-perf-batches"
    else
      printf '%s' "${LOCALAPPDATA}/account-service-perf-batches"
    fi
  else
    printf '%s' "${TMPDIR:-/tmp}/account-service-perf-batches"
  fi
}

publish_with_awscli() {
  require_cmd aws
  echo "WARN: using AWS CLI publisher (slow on Windows — prefer SDK engine)" >&2

  plan_file="${WORK_DIR}/publish_plan.csv"
  : > "${plan_file}"
  while IFS=, read -r acct owner; do
    [[ -z "$acct" ]] && continue
    idx=$START_INDEX
    while [[ $idx -le $END_INDEX ]]; do
      echo "${acct},${owner},${idx}" >> "${plan_file}"
      idx=$((idx + 1))
    done
  done < "${ACCOUNTS_FILE}"

  TOTAL_LINES=$(wc -l < "${plan_file}" | tr -d ' ')
  echo "Publishing ${TOTAL_LINES} messages (idx ${START_INDEX}..${END_INDEX}) with ${PUBLISH_WORKERS} CLI workers to ${SQS_QUEUE_URL}"

  ROOT="$(batch_root)"
  mkdir -p "${ROOT}"

  publish_chunk() {
    local chunk_file="$1"
    local worker_id="$2"
    local batch_dir="${ROOT}/w${worker_id}.$$"
    mkdir -p "${batch_dir}"
    local n=0 batch_id=0 acct owner idx micros balance tx corr body body_esc entries_file entry entries_ref
    local entries_json=""
    local acct_ord=0
    # shellcheck disable=SC2034
    local run_h0="${RUN_HASH:0:4}" run_h1="${RUN_HASH:4:4}"

    # Build account ordinal map once per worker from full accounts file order.
    declare -A ACCT_ORD=()
    acct_ord=0
    while IFS=, read -r a _o; do
      [[ -z "$a" ]] && continue
      acct_ord=$((acct_ord + 1))
      ACCT_ORD["$a"]=$acct_ord
    done < "${ACCOUNTS_FILE}"

    gen_tx_uuid() {
      local account_id="$1" event_idx="$2" ord packed
      ord=${ACCT_ORD["$account_id"]}
      packed=$(( (ord << 20) | (event_idx & 0xFFFFF) ))
      printf 'bbbbbbbb-%s-%s-8eee-%012x' "${RUN_HASH:0:4}" "${RUN_HASH:4:4}" "$packed"
    }

    aws_entries_ref() {
      local f="$1" drive rest
      if command -v cygpath >/dev/null 2>&1; then
        printf 'file://%s' "$(cygpath -w "$f" | sed 's|\\|/|g')"
        return 0
      fi
      if [[ "$f" =~ ^/([a-zA-Z])/(.*)$ ]]; then
        drive=$(printf '%s' "${BASH_REMATCH[1]}" | tr '[:lower:]' '[:upper:]')
        rest="${BASH_REMATCH[2]}"
        printf 'file://%s:/%s' "$drive" "$rest"
        return 0
      fi
      printf 'file://%s' "$f"
    }

    flush_batch() {
      if [[ $n -eq 0 ]]; then
        return 0
      fi
      entries_file="${batch_dir}/batch-${batch_id}.json"
      printf '%s\n' "$entries_json" > "${entries_file}"
      entries_ref=$(aws_entries_ref "$entries_file")
      aws_local sqs send-message-batch \
        --queue-url "${SQS_QUEUE_URL}" \
        --entries "${entries_ref}" >/dev/null
      batch_id=$((batch_id + 1))
      entries_json=""
      n=0
    }

    while IFS=, read -r acct owner idx; do
      [[ -z "$acct" ]] && continue
      micros=$((BASE_MICROS + idx))
      balance=$(awk -v i="$idx" 'BEGIN{printf "%.2f", i}')
      tx=$(gen_tx_uuid "$acct" "$idx")
      corr="perf-${acct}-${idx}"
      body="{\"transaction\":{\"id\":\"${tx}\",\"type\":\"CREDIT\",\"amount\":\"10.00\",\"currency\":\"BRL\",\"status\":\"APPROVED\",\"timestamp\":${micros}},\"account\":{\"id\":\"${acct}\",\"owner\":\"${owner}\",\"created_at\":1609459200,\"status\":\"ENABLED\",\"balance\":{\"amount\":${balance},\"currency\":\"BRL\"}}}"
      body_esc=$(json_escape "$body")
      entry="{\"Id\":\"${n}\",\"MessageBody\":\"${body_esc}\",\"DelaySeconds\":${DELAY_SECONDS},\"MessageAttributes\":{\"${CORRELATION_ATTR}\":{\"DataType\":\"String\",\"StringValue\":\"${corr}\"}}}"
      if [[ "$DELAY_SECONDS" -eq 0 ]]; then
        entry="{\"Id\":\"${n}\",\"MessageBody\":\"${body_esc}\",\"MessageAttributes\":{\"${CORRELATION_ATTR}\":{\"DataType\":\"String\",\"StringValue\":\"${corr}\"}}}"
      fi
      if [[ $n -eq 0 ]]; then
        entries_json="[${entry}"
      else
        entries_json="${entries_json},${entry}"
      fi
      n=$((n + 1))
      if [[ $n -ge 10 ]]; then
        entries_json="${entries_json}]"
        flush_batch
      fi
    done < "${chunk_file}"
    if [[ $n -gt 0 ]]; then
      entries_json="${entries_json}]"
      flush_batch
    fi
    rm -rf "${batch_dir}"
  }

  split_dir="${ROOT}/chunks.$$"
  rm -rf "${split_dir}"
  mkdir -p "${split_dir}"
  worker=0
  while IFS= read -r line; do
    echo "$line" >> "${split_dir}/chunk-$(printf '%02d' "$worker")"
    worker=$(( (worker + 1) % PUBLISH_WORKERS ))
  done < "${plan_file}"

  start_ts=$(date +%s)
  pids=()
  wid=0
  for chunk in "${split_dir}"/chunk-*; do
    [[ -f "$chunk" ]] || continue
    publish_chunk "$chunk" "$wid" &
    pids+=($!)
    wid=$((wid + 1))
  done
  fail=0
  for pid in "${pids[@]}"; do
    if ! wait "$pid"; then
      fail=1
    fi
  done
  rm -rf "${split_dir}"
  end_ts=$(date +%s)
  if [[ "$fail" -ne 0 ]]; then
    echo "ERROR: one or more publish workers failed" >&2
    exit 1
  fi
  elapsed=$((end_ts - start_ts))
  [[ "$elapsed" -lt 1 ]] && elapsed=1
  rate=$(awk -v n="$TOTAL_LINES" -v t="$elapsed" 'BEGIN{printf "%.1f", n/t}')
  echo "Publish complete: messages=${TOTAL_LINES} elapsed_s=${elapsed} publish_rate_msg_s=${rate}"
  echo "${TOTAL_LINES}" > "${WORK_DIR}/published_count.txt"
  echo "${elapsed}" > "${WORK_DIR}/publish_elapsed_s.txt"
  echo "${rate}" > "${WORK_DIR}/publish_rate.txt"
}

case "${PUBLISH_ENGINE}" in
  sdk)
    publish_with_sdk
    ;;
  awscli)
    publish_with_awscli
    ;;
  auto|*)
    if publish_with_sdk; then
      exit 0
    fi
    echo "WARN: SDK publisher failed — falling back to AWS CLI" >&2
    publish_with_awscli
    ;;
esac
