# Benchmarks

Every number below comes from a committed raw file in `reports/`. Each file starts with a `machine` line recording the hardware, Docker settings and git commit it was measured at.

## Phase 2: oversell test

`bench/oversell_test.py` releases N concurrent single-unit reservation requests at once, using a barrier, against one SKU with 50 units. It then checks Postgres directly. A run passes only if exactly 50 requests get 201, the rest get 409, there are no other status codes, `reserved` equals the number of 201s, the reservation lines match `reserved`, and oversold = 0.

| concurrency | runs | runs passed | requests | oversold units | raw |
|---|---|---|---|---|---|
| 200 | 20 | 20 | 4,000 | 0 | `reports/phase2/oversell-c200.jsonl` |
| 1000 | 20 | 20 | 20,000 | 0 | `reports/phase2/oversell-c1000.jsonl` |

- **Machine:** GitHub Codespace, 2 vCPU AMD EPYC 7763, 8 GB RAM, Docker 28.1.1. The load generator ran on the same machine as the stack.
- **Commit:** `0031162`, tree clean.
- **Strategy:** conditional update.
- **Latency:** per-run p95 ranged from 417 to 1,729 ms at concurrency 200 and from 1,248 to 4,052 ms at 1000. That is queueing: 1000 simultaneous connections share 200 Tomcat threads and 10 database connections on 2 vCPUs. These are correctness tests, not latency benchmarks.

## Phase 2: reserve strategy comparison

See ADR-007 in `docs/decisions.md` for the table and analysis. Raw output is in `reports/phase2/reserve-strategies.jsonl`.

## Phase 2: mutation check

`scripts/mutation_check.py phase2` removes one guard at a time (the availability predicate, `FOR UPDATE`, the version check, sku sorting, `ON CONFLICT`, the legal-source status filter, the expiry status filter, the tombstone, and expired-release handling). For each one it confirms the integration tests fail. Result: `reports/phase2/mutation-check.txt`.

## Phase 3 / 4: mutation check

| phase | mutants | killed | raw |
|---|---|---|---|
| 3 (payment) | 3 | 3 | `reports/phase4/mutation-check-phase3.txt` |
| 4 (order saga, outbox, idempotency, lease) | 6 | 6 | `reports/phase4/mutation-check-phase4.txt` |

Each run first checks that the unmutated code passes the same test selection.

## Phase 4: duplicate-request test

`bench/duplicate_test.py`: 500 logical orders. Each is sent 4 times with the same Idempotency-Key and body: 2 copies race each other, then 2 late retries follow. The script then checks the three databases directly.

| logical orders | requests sent | duplicates | orders in DB | keys with exactly 1 order | cross-service violations | raw |
|---|---|---|---|---|---|---|
| 500 | 2,000 | 1,500 | 500 (all CONFIRMED) | 500 | 0 | `reports/phase4/duplicate-test.jsonl` |

The client also got 1,448 non-final answers (409 in-progress while its twin was running the saga) and retried them with the same key. The checks: at most one live payment per order, payment and reservation state match the order, stock `reserved` equals live holds, and the outbox is drained. All of these are in `bench/_invariants.py`.

## Phase 4: chaos test

`bench/chaos_test.py`, 150 orders per scenario. After each scenario the script waits for convergence and runs the same invariants. The process kills are real `docker compose kill -s SIGKILL`.

| scenario | what was broken | outcome (orders by final status) | violations |
|---|---|---|---|
| faults | payment injection (seed 7): 10% declines, 20% timeouts of 3 s (longer than order-service's 2 s read timeout, so the charge commits and the response is lost), 30% capture failures | 140 PICKED_UP, 10 CANCELLED (PAYMENT_DECLINED, 10 injected declines) | 0 |
| kill-payment | payment-service SIGKILLed 1.5 s into the load, back after 21.0 s | 134 CONFIRMED, 16 CANCELLED (PAYMENT_UNAVAILABLE, compensated) | 0 |
| kill-order | order-service (orchestrator + relay) SIGKILLed 1.5 s in, back after 20.7 s; clients retried 356 times with the same keys | 150 CONFIRMED | 0 |
| kill-relay | order-service SIGKILLed during 150 pickups, while the relay was publishing OrderPickedUp | 150 PICKED_UP, 150 reservations CONSUMED (each exactly once) | 0 |

Raw output, including Resilience4j retry-counter deltas for the faults scenario, is in `reports/phase4/chaos-test.jsonl`.

- **Machine:** Codespace, 2 vCPU AMD EPYC 7763, 8 GB RAM, Docker 28.1.1.
- **Commit:** `380421f`, tree clean.
- **Memory after the run:** order 238 MiB, inventory 256 MiB, payment 204 MiB, Kafka 504 MiB, Postgres 163 MiB, Redis 13 MiB (`reports/phase4/docker-stats.txt`).
