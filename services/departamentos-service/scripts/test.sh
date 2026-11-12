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
awk -v coverage="$coverage" 'BEGIN {
  printf "Cobertura global: %.1f%%\n", coverage
  if (coverage < 70) {
    print "La cobertura global es inferior al 70%" > "/dev/stderr"
    exit 1
  }
}'
