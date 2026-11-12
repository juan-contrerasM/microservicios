#!/bin/sh
set -eu

mkdir -p reports

go run gotest.tools/gotestsum@v1.12.3 \
  --junitfile reports/junit.xml \
  -- \
  -coverprofile=coverage.out \
  -covermode=atomic \
  ./...

coverage="$(go tool cover -func=coverage.out | awk '/^total:/ {gsub(/%/, "", $3); print $3}')"
# Fase 9: COBERTURA_MINIMA permite al pipeline simular un fallo de cobertura sin tocar código.
minimo="${COBERTURA_MINIMA:-70}"
awk -v coverage="$coverage" -v minimo="$minimo" 'BEGIN {
  printf "Cobertura global: %.1f%%\n", coverage
  if (coverage < minimo) {
    printf "La cobertura global es inferior al %s%%\n", minimo > "/dev/stderr"
    exit 1
  }
}'
