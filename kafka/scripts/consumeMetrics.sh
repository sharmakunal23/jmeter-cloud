#!/bin/bash
# consumeMetrics.sh
#
# Consumes WorkerMetric messages from the local Kafka topic and prints
# a human-readable summary of each record as it arrives.
#
# Requires: kcat (brew install kcat)
#
# Usage:
#   ./scripts/consumeMetrics.sh
#
# The output is JSON — pipe through jq for pretty formatting:
#   ./scripts/consumeMetrics.sh | jq .

set -euo pipefail

if ! command -v kcat &>/dev/null; then
  echo "kcat not found. Install it:"
  echo "  brew install kcat"
  exit 1
fi

echo "Consuming from jmeter.metrics.perSecond (Ctrl-C to stop)..."
echo "Open http://localhost:8080 for the full Kafka UI view."
echo ""

# kcat in consumer mode (-C), reading from the beginning (-o beginning),
# printing key and value as JSON-friendly output.
# Note: values are Avro binary — the Schema Registry URL decodes them.
kcat \
  -b localhost:9092 \
  -t jmeter.metrics.perSecond \
  -C \
  -o beginning \
  -s value=avro \
  -r http://localhost:8081 \
  -f "Key: %k\nValue: %s\n---\n"
