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
