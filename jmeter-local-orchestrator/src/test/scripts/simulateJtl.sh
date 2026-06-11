#!/bin/bash
# simulate-jtl.sh
#
# Simulates JMeter writing a JTL file for local orchestrator testing.
# Does NOT require a running JMeter or target application.
#
# Writes realistic JTL rows at ~20 req/s for 30 seconds, then writes the
# sentinel file. The orchestrator should detect and stream all rows to Kafka.
#
# Usage:
#   chmod +x simulate-jtl.sh
#   ./simulate-jtl.sh
#
# In a separate terminal, start the orchestrator first:
#   set -a && source .env.local && set +a
#   java -jar target/jmeter-local-orchestrator-*.jar

set -euo pipefail

JTL_DIR="/tmp/jmeter-results"
JTL_FILE="$JTL_DIR/results.jtl"
SENTINEL="$JTL_DIR/.done"
STATE_FILE="$JTL_DIR/.jtlOffset"

# Clean up any previous run
mkdir -p "$JTL_DIR"
rm -f "$JTL_FILE" "$SENTINEL" "$STATE_FILE"

echo "Writing JTL to: $JTL_FILE"
echo "Orchestrator should be running and watching this file."
echo ""

# JTL header
echo "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect" \
  > "$JTL_FILE"

LABELS=(
  "POST /api/payment"
  "GET /api/account"
  "POST /api/auth"
  "GET /api/transactions"
  "PUT /api/profile"
)

ROWS_PER_SECOND=20
DURATION_SECONDS=300
ERROR_RATE=5   # ~5% of rows are errors

echo "Simulating ${ROWS_PER_SECOND} req/s for ${DURATION_SECONDS}s (${DURATION_SECONDS} seconds total)"
echo "Press Ctrl-C to stop early."
echo ""

for (( s=0; s<DURATION_SECONDS; s++ )); do
  # Format current timestamp as yyyy/MM/dd HH:mm:ss
  TS=$(date "+%Y/%m/%d %H:%M:%S")

  for (( r=0; r<ROWS_PER_SECOND; r++ )); do
    # Pick a label (round-robin across endpoints)
    LABEL="${LABELS[$((r % ${#LABELS[@]}))]}"

    # Random elapsed: 80–400ms, with occasional 1000–5000ms spike
    ROLL=$((RANDOM % 100))
    if [ $ROLL -lt 3 ]; then
      ELAPSED=$(( 1000 + RANDOM % 4000 ))  # slow spike
    else
      ELAPSED=$(( 80 + RANDOM % 320 ))
    fi

    # Simulate errors
    IS_ERROR=$(( RANDOM % 100 < ERROR_RATE ))
    if [ "$IS_ERROR" -eq 1 ]; then
      CODE=503
      MSG="Service Unavailable"
      SUCCESS=false
      FAIL_MSG="Response code was 503"
    else
      CODE=200
      MSG="OK"
      SUCCESS=true
      FAIL_MSG=""
    fi

    LATENCY=$(( ELAPSED - 2 ))
    BYTES=$(( 512 + RANDOM % 2048 ))
    SENT=$(( 256 + RANDOM % 512 ))
    THREADS=$(( 10 + r / 2 ))

    printf '%s,%d,%s,%d,%s,jmeter-worker-0 %d-%d,text,%s,"%s",%d,%d,%d,%d,https://app%s,%d,0,2\n' \
      "$TS" "$ELAPSED" "$LABEL" "$CODE" "$MSG" \
      "$((s+1))" "$((r+1))" \
      "$SUCCESS" "$FAIL_MSG" \
      "$BYTES" "$SENT" "$THREADS" "$THREADS" \
      "$LABEL" "$LATENCY" \
      >> "$JTL_FILE"
  done

  printf "\r  Second %2d/%d — wrote %d rows (total: %d)" \
    "$((s+1))" "$DURATION_SECONDS" "$ROWS_PER_SECOND" "$(( (s+1) * ROWS_PER_SECOND ))"

  sleep 1
done

echo ""
echo ""
echo "Writing sentinel file (simulating JMeter exit code 0)..."
echo "0" > "$SENTINEL"
echo "Done. Orchestrator should drain remaining rows and stop."
echo ""
echo "Check Kafka UI at http://localhost:8080 to inspect published messages."
