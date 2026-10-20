#!/usr/bin/env bash
# Phase 1 acceptance check: every compose container is healthy and every service reports UP.
# Usage: scripts/smoke.sh   (after `docker compose up -d --wait`)
set -euo pipefail

fail=0
echo "== container health =="
for c in postgres redis kafka order-service inventory-service payment-service; do
  id=$(docker compose ps -q "$c")
  state=$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo missing)
  printf '%-18s %s\n' "$c" "$state"
  [[ "$state" == healthy ]] || fail=1
done

echo "== service health endpoints =="
for port in 8081 8082 8083; do
  body=$(curl -fsS "http://127.0.0.1:${port}/actuator/health" || echo '{"status":"UNREACHABLE"}')
  echo "127.0.0.1:${port} ${body}"
  [[ "$body" == *'"status":"UP"'* ]] || fail=1
done

echo "== infra probes =="
docker compose exec -T postgres psql -U postgres -tAc "select string_agg(datname, ',' order by datname) from pg_database where datname in ('orders','inventory','payments')"
docker compose exec -T redis redis-cli ping
docker compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 | head -1

if [[ $fail -ne 0 ]]; then echo "SMOKE: FAIL"; exit 1; fi
echo "SMOKE: PASS"
