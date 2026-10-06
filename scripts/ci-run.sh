#!/usr/bin/env bash
# Runs a gradle task and surfaces the first failure lines as GitHub annotations,
# so build errors are visible through the API as well as in the raw logs.
set -o pipefail
LABEL="$1"; shift
LOG="/tmp/${LABEL}.log"
"$@" 2>&1 | tee "$LOG"
STATUS=$?
if [ $STATUS -ne 0 ]; then
  echo "::group::${LABEL} failure summary"
  grep -nE "^e: |error:|FAILED|Caused by|Execution failed|Unresolved reference|> Task .* FAILED|What went wrong" "$LOG" \
    | head -n 60 | while IFS= read -r line; do
      clean=$(printf '%s' "$line" | tr -d '\r' | sed 's/%/%25/g; s/\r/%0D/g')
      echo "::error title=${LABEL}::${clean}"
    done
  echo "::endgroup::"
fi
exit $STATUS
