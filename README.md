# PickupDesk

A buy-online-pickup-in-store order and inventory platform. This is an independent portfolio project and is not affiliated with any company.

**Status:** Phase 1 (skeleton and CI). The feature list below includes only what is implemented and tested. See `docs/spec.md` for the full plan and `docs/decisions.md` for design decisions.

## What works today

- Three Spring Boot 3.5 services on Java 21: `order-service`, `inventory-service`, `payment-service`. Each exposes `/actuator/health`, `/actuator/prometheus`, and `/v3/api-docs`, with one database per service.
- `docker compose up` starts Postgres 16, Redis 7, Kafka 3.9 (single-node KRaft) and the three services, all with healthchecks.
- CI (GitHub Actions) runs the build, unit tests, and Testcontainers integration tests, then starts the full compose stack and runs a smoke test.

## Run locally

```bash
docker compose up -d --build --wait   # first build downloads Maven deps, takes a few minutes
scripts/smoke.sh                      # checks every container is healthy and every service is UP
docker compose down -v
```

Service ports (localhost only): order 8081, inventory 8082, payment 8083.

## Build and test

```bash
./mvnw verify          # unit tests + Testcontainers ITs (needs Docker)
./mvnw test            # unit tests only
```
