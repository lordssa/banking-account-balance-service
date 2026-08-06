#!/usr/bin/env bash
# Apache Bench–style wrapper around k6-ab-like.js
#
# Usage:
#   ./deploy/perf/k6-ab.sh -c 10 -n 20 'http://localhost:8080/balances/<uuid>'
#   ./deploy/perf/k6-ab.sh -c 5 -n 100 -m GET 'http://localhost:8080/internal/journal/accounts/<uuid>'
#   EXPECT_STATUS=404 ./deploy/perf/k6-ab.sh -c 1 -n 5 'http://localhost:8080/balances/00000000-0000-4000-8000-000000000000'
#
# Flags (same spirit as ab):
#   -c  concurrency (VUs)
#   -n  total requests
#   -m  HTTP method (default GET)
#   -h  help
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

C=1
N=1
METHOD=GET
URL=""

usage() {
  sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

while getopts ':c:n:m:h' opt; do
  case "$opt" in
    c) C="$OPTARG" ;;
    n) N="$OPTARG" ;;
    m) METHOD="$OPTARG" ;;
    h) usage 0 ;;
    *) usage 1 ;;
  esac
done
shift $((OPTIND - 1))

URL="${1:-}"
if [[ -z "$URL" ]]; then
  echo "ERROR: URL is required" >&2
  usage 1
fi

if ! command -v k6 >/dev/null 2>&1; then
  echo "ERROR: k6 is required — https://k6.io/docs/get-started/installation/" >&2
  exit 1
fi

export URL C N METHOD
export VUS="$C"
export REQUESTS="$N"

echo "→ k6 ab-like  -c ${C} -n ${N} -m ${METHOD}  ${URL}"
exec k6 run "${SCRIPT_DIR}/k6-ab-like.js"
