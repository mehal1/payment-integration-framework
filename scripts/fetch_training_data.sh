#!/usr/bin/env bash
# Fetch risk training data CSV from the running app. Saves to /tmp first, then copies to current dir.
# Usage: ./scripts/fetch_training_data.sh [rows]
# Example: ./scripts/fetch_training_data.sh 500
# Requires: app running on http://localhost:8080

set -e
ROWS="${1:-500}"
URL="http://localhost:8080/api/v1/risk/demo/training-data?rows=${ROWS}"
TMP="/tmp/risk_training_data.csv"
OUT="risk_training_data.csv"

echo "Fetching ${ROWS} rows from ${URL} ..."
if ! curl -sS "$URL" -o "$TMP"; then
  echo "curl failed. Is the app running? Try: ./mvnw spring-boot:run"
  exit 1
fi

if [ ! -s "$TMP" ]; then
  echo "Response is empty. Check that the app is running and the endpoint is available."
  exit 1
fi

lines=$(wc -l < "$TMP")
echo "Saved ${lines} lines to ${TMP}"
head -3 "$TMP"

cp "$TMP" "$OUT"
echo "Copied to $(pwd)/${OUT}"
