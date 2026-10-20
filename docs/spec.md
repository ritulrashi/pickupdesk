# PickupDesk: Buy-Online-Pickup-In-Store Order and Inventory Platform

Independent portfolio project. Not affiliated with any company. Inspired by the kind of retail order, inventory, and payment systems a large retailer's internal engineering org runs.

Target role: Software Engineer, IS&T Early Career (Retail & Sales Engineering / Enterprise Technology Services flavor). Stack matches the posting: Java server-side, Python scripting, JavaScript/HTML/CSS, AWS foundational services, CS fundamentals, testing.

## 0. Rules for Claude Code

See `CLAUDE.md` at the repo root.

## 1. Why this project (JD mapping)

| Posting requirement | Where it shows up |
|---|---|
| Server-side Java | Three Spring Boot 3 services (Java 21) |
| Python or scripting | tools/reconcile.py nightly reconciliation, bench/ load and chaos drivers |
| JavaScript, HTML, CSS | Store associate pickup console (vanilla JS or React, plus HTML/CSS) |
| CS fundamentals | Concurrency control, idempotency, state machines, caching, indexing |
| AWS foundational services | EC2, RDS Postgres, S3 (nightly reports), CloudWatch logs |
| Testing / quality (SDET, SRE team types) | Testcontainers integration tests, contract tests, load tests, chaos test |
| Leadership / quantitative | Measured benchmarks with written analysis |
| "Billions of secure transactions" | Payment idempotency, tokenized payments, audit trail |

Overlap check against existing portfolio: Midas Core already covers Kafka fan-out, a double-entry ledger, and a DLQ. This project must NOT repeat those. New depth here: inventory reservation concurrency, saga orchestration with compensation, transactional outbox, cache invalidation, order state machine, returns/refunds, an associate-facing web UI, AWS deployment.

## 2. Product scenario (what the system does)

A customer orders a product online and picks it up at a store.

1. Customer places an order for SKU(s) at a chosen store.
2. System reserves stock at that store (reservation expires after 15 minutes if payment fails or is not completed).
3. System authorizes payment through a simulated payment gateway using a card token (never a raw card number).
4. Order is confirmed; store associate sees it in a pickup queue.
5. Associate marks "ready", customer arrives, associate verifies pickup code and completes the handoff. Payment is captured at handoff.
6. Customer may return the item: refund issued, stock restored.

Failure paths are the point of the project: payment declined, gateway timeout, duplicate client retry, service crash mid-order, two customers buying the last unit at once, reservation expiry.

Non-goals: real payment processors, real card data, user accounts UI, recommendation features, mobile apps.

## 3. Architecture

Three services plus infrastructure, deliberately fewer than Midas Core's seven.

- **order-service** (Spring Boot): owns orders, the order state machine, saga orchestration, outbox table, public REST API.
- **inventory-service** (Spring Boot): owns stock per (store, sku), reservations, expiry job, Redis availability cache.
- **payment-service** (Spring Boot): simulated gateway. Authorize, capture, void, refund. Idempotency keys. Configurable failure injection (decline rate, latency, timeout) via an admin endpoint so chaos tests are reproducible.
- **pickup-console** (static HTML/CSS/JS served by nginx): associate queue, mark ready, verify pickup code, complete handoff.
- **Infra**: PostgreSQL (one database per service, schemas separate), Redis, Kafka (KRaft single node), Prometheus, Grafana.
- **Python tools**: tools/reconcile.py (compares orders vs payments vs inventory, outputs a discrepancy report, uploads to S3 in AWS mode), bench/*.py or k6 scripts.

Communication:

- Synchronous REST for reserve and authorize calls from order-service (with timeouts and retries).
- Asynchronous Kafka events via transactional outbox: order-service writes the event row in the same DB transaction as the state change; a relay publishes to Kafka. Consumers dedupe by event id.

Order state machine (enforce in code, reject illegal transitions, test every illegal transition):

```
CREATED -> RESERVED -> PAYMENT_AUTHORIZED -> CONFIRMED -> READY_FOR_PICKUP -> PICKED_UP -> (RETURNED)
Failure exits: CREATED|RESERVED -> CANCELLED, RESERVED -> PAYMENT_FAILED -> CANCELLED, CONFIRMED -> EXPIRED -> CANCELLED.
```

Saga (orchestrated by order-service):

1. Reserve inventory. On failure, order CANCELLED (out of stock).
2. Authorize payment. On decline or timeout, release reservation (compensation), order PAYMENT_FAILED -> CANCELLED.
3. Confirm order, emit OrderConfirmed.
4. At pickup: capture payment. If capture fails, order stays READY_FOR_PICKUP and capture is retried; alert after N failures.

Compensation steps are themselves idempotent.

## 4. Data model (minimum)

order-service:

- `orders(id uuid pk, customer_ref, store_id, status, total_cents, pickup_code_hash, created_at, updated_at, version)`
- `order_items(order_id, sku, qty, unit_price_cents)`
- `order_events(id, order_id, type, payload jsonb, created_at)` (audit trail, append-only)
- `outbox(id uuid pk, aggregate_id, type, payload jsonb, created_at, published_at null)`
- `idempotency_keys(key pk, request_hash, response_body, status, created_at)`

inventory-service:

- `stock(store_id, sku, on_hand, reserved, pk(store_id, sku), check(on_hand >= 0), check(reserved >= 0), check(reserved <= on_hand))`
- `reservations(id, order_id unique, store_id, sku, qty, status, expires_at)`

payment-service:

- `payments(id, order_id, token, amount_cents, status, idempotency_key unique, created_at)`
- `payment_events(id, payment_id, type, created_at)`

Rules:

- Money in integer cents. Never floating point.
- Pickup code is random, stored hashed, shown once.
- Database CHECK constraints are the last line of defense against overselling, in addition to locking.

## 5. Concurrency and correctness requirements (the interview core)

- **No overselling.** Reserve uses a single atomic conditional update or SELECT ... FOR UPDATE. Document why you chose it and what you rejected (optimistic locking with version retries). Write an ADR comparing pessimistic locking vs conditional update under contention, backed by a benchmark if cheap.
- **Idempotent order creation.** Client sends Idempotency-Key header. Same key + same body returns the original response. Same key + different body returns 422. Concurrent duplicates must result in one order.
- **Idempotent payment.** Authorize with same key returns same payment, never a second charge.
- **Outbox relay is at-least-once**, consumers are idempotent, net effect effectively-once. Test by killing the relay mid-publish.
- **Reservation expiry job** releases stock for abandoned reservations. Must be safe if run twice or by two instances (use FOR UPDATE SKIP LOCKED).
- **Cache correctness.** Redis caches availability reads only. Writes go to Postgres. Invalidate on stock change events. Document staleness window. Reservation always checks the database, never the cache.
- **Timeouts and retries.** Every outbound HTTP call has a timeout, bounded retries with backoff and jitter, and a circuit breaker (Resilience4j). Document values.
- **Security.** Input validation, parameterized queries, service-to-service auth via a shared signed JWT (HS256 is fine, note the tradeoff), RBAC: CUSTOMER, ASSOCIATE, ADMIN. Associates can only act on orders for their own store (test the IDOR case). Never log tokens or pickup codes.

## 6. API sketch

order-service:

- `POST /api/orders` (Idempotency-Key required)
- `GET /api/orders/{id}`
- `POST /api/orders/{id}/cancel`
- `GET /api/stores/{storeId}/pickup-queue` (ASSOCIATE)
- `POST /api/orders/{id}/ready` (ASSOCIATE)
- `POST /api/orders/{id}/pickup` with pickup code (ASSOCIATE)
- `POST /api/orders/{id}/return` (ASSOCIATE)

inventory-service:

- `GET /api/inventory/{storeId}/{sku}` (cached)
- `POST /internal/reservations`, `DELETE /internal/reservations/{orderId}` (service-to-service only)
- `POST /admin/stock` (ADMIN, for seeding and restock)

payment-service:

- `POST /internal/payments/authorize`, `/capture`, `/void`, `/refund`
- `POST /admin/failure-injection` (ADMIN): decline rate, added latency, timeout rate

All services expose `/actuator/health` and `/actuator/prometheus`. Provide an OpenAPI spec per service.

## 7. Build phases and acceptance criteria

**Phase 1: Skeleton and CI (half day)**
Monorepo, Gradle or Maven multi-module, three Spring Boot apps, docker compose with Postgres, Redis, Kafka. GitHub Actions: build, unit tests, Testcontainers integration tests.
Done when: docker compose up starts everything healthy; CI is green.

**Phase 2: Inventory with correct concurrency (1 day)**
Stock and reservation tables, reserve/release API, CHECK constraints, expiry job with SKIP LOCKED. bench/oversell_test: 200 to 1000 concurrent reservation attempts against stock of 50. Assert exactly 50 succeed, on_hand/reserved invariants hold, zero oversold.
Done when: oversell test passes repeatedly (run it at least 20 times, record results).

**Phase 3: Payment simulator with idempotency (half day)**
Authorize/capture/void/refund, idempotency keys, failure-injection admin endpoint.
Done when: duplicate-authorize test yields one payment; injected timeout test behaves as documented.

**Phase 4: Order saga, state machine, outbox (2 days)**
Order creation with idempotency, saga with compensation, state machine with illegal-transition tests, outbox plus relay, Kafka consumers with dedupe, audit table.
Done when: all failure paths in section 2 have an automated test. Kill-the-relay and kill-payment-service tests pass. Duplicate-request test (e.g. 500 duplicated creates, one order each) passes.

**Phase 5: Redis cache, observability, security (1 day)**
Availability cache with event-driven invalidation. Micrometer metrics: order create latency histogram, reservation latency, saga failures by reason, outbox lag, cache hit ratio. Grafana dashboard JSON committed. JWT and RBAC with IDOR tests.
Done when: cache A/B benchmark (cache on vs off) recorded. Dashboard screenshots in /docs.

**Phase 6: Pickup console and Python tooling (1 day)**
Static HTML/CSS/JS page: login with associate token, pickup queue, mark ready, enter pickup code. Keep it small and accessible (labels, keyboard focus, contrast). tools/reconcile.py: reads from the three databases (read-only), reports orders without payments, payments without orders, reservations stuck past expiry, stock invariant violations. Unit tests with pytest.
Done when: reconcile finds seeded discrepancies and reports zero on clean runs.

**Phase 7: AWS deployment (1 day, free tier friendly)**
One EC2 instance running docker compose (or ECS if the author prefers), RDS PostgreSQL for the order DB at minimum, S3 bucket receiving nightly reconcile reports, CloudWatch logs agent or awslogs driver. Terraform or documented CLI steps. Deploy job in GitHub Actions on tag.
Done when: public (or SSH-tunnelled) health check works; a report appears in S3; teardown instructions exist so it does not run up a bill. Do not claim AWS in the resume unless this phase is actually done.

**Phase 8: Write-up (half day)**
README with architecture diagram, run instructions, honest limitations, BENCHMARKS.md with raw output, docs/decisions.md.

## 8. Measurement plan (what produces each resume number)

| Resume placeholder | Source | How to measure |
|---|---|---|
| [N] concurrent reservation requests, [R] runs, zero oversold | bench/oversell_test | Fixed concurrency, repeated runs, record oversold count each run |
| [X]% p95 latency cut, [N] req/s | k6 or Gatling, cache off vs on | Same hardware, same dataset, warm-up excluded, 5 repeated runs, report median |
| [N] injected duplicates and failures | bench/duplicate_test, bench/chaos_test | Count duplicates sent, count resulting orders and charges (must match 1:1) |
| [N] tests, [C]% coverage | JaCoCo (Java) and pytest-cov (Python) | CI artifact |
| Dashboard alerting | Grafana and Prometheus rules committed | Screenshot plus rule file |

Hardware note: record the machine (CPU, RAM, docker settings) next to every benchmark. Numbers from a laptop under load are fine if labeled as such. Interviewers care that they are real and reproducible, not large.

## 9. Resume bullets to fill in after measuring

Each is 15 to 19 words with placeholders. Replace placeholders only with measured values. If a metric disappoints, remove the number and keep the sentence honest rather than inflating it.

1. Built Java Spring Boot order, inventory, and payment services handling buy-online-pickup-in-store flows with saga-based compensation on failure.
2. Eliminated overselling across [N] concurrent reservation requests using PostgreSQL row locking, verified with zero oversold units in [R] runs.
3. Cut p95 availability-lookup latency [X]% by adding Redis caching with event-driven invalidation, load-tested at [N] requests per second.
4. Guaranteed effectively-once order effects across [N] injected duplicates and failures using idempotency keys and a transactional outbox with Kafka.
5. Deployed Dockerized services to AWS (EC2, RDS, S3) through GitHub Actions CI/CD with [N] automated tests at [C]% coverage.
6. Instrumented services with Micrometer and Prometheus, alerting on order failure rate and reservation latency through Grafana dashboards.

Use bullets 1, 2, 3, 4 on the resume (5 if AWS is done and space allows). Project title line: "PickupDesk: Retail Order and Inventory Platform (Java, Spring Boot, PostgreSQL, Redis, Kafka, AWS)". Add "(independent project)" if space allows.

## 10. Interview defense checklist

Be ready to answer without notes:

- Why saga with orchestration instead of two-phase commit? What breaks if the orchestrator crashes after reserve but before authorize?
- Why transactional outbox? What failure does it prevent (dual write)? What does it cost (relay lag, duplicates)?
- Pessimistic locking vs conditional update vs optimistic versioning for stock: which did you choose, what did the benchmark show?
- How do you guarantee one charge if the client retries after a timeout? What if the key is reused with a different body?
- Cache invalidation: what is the staleness window and why is it acceptable for reads but not for reserves?
- What happens when two expiry jobs run at once?
- How do you prevent an associate at store A from touching store B's order?
- What would you change at 100x scale (partitioning stock by store, read replicas, Kafka partitions keyed by order id, per-store sharding)?
- What is the weakest part of this project? (Have a real answer: for example, simulated gateway, single-node Kafka, HS256 shared secret.)

## 11. Prompts to give Claude Code, in order

Start with: "Read CLAUDE.md and docs/spec.md. Do Phase 1 only. Stop when its acceptance criteria pass and show me the evidence." Then repeat for each phase, replacing the phase number. After Phase 2, 4, and 5, add: "Run the benchmark scripts, commit raw output to /reports, and update BENCHMARKS.md. Do not estimate anything."

When all phases are done: "Audit the repo against the README. List every claim that is not backed by a test or a committed benchmark result."
