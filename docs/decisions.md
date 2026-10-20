# Design decisions

Short ADRs. Each one: context, decision, alternatives considered, consequence.

## ADR-001: Maven multi-module build

- **Context:** Three Spring Boot services in one repo need one build that CI and Docker both run.
- **Decision:** Maven multi-module with `spring-boot-starter-parent` as the parent and the Maven wrapper pinned to 3.9.11.
- **Alternatives:** Gradle (faster incremental builds, more flexible), separate repos per service.
- **Consequence:** XML is verbose, but the build is declarative and every Spring guide uses it. `-pl <service> -am` builds one service and only what it depends on. Unit tests (`*Test`, surefire) and Testcontainers integration tests (`*IT`, failsafe) are split by naming convention, so `mvn test` never needs Docker.

## ADR-002: One Postgres instance, one database and owner role per service

- **Context:** The spec requires each service to own its data. The stack must fit in 8 GB of RAM.
- **Decision:** A single Postgres 16 container holds three databases (`orders`, `inventory`, `payments`). Each database is owned by its own login role, and `PUBLIC` has no access to any of them.
- **Alternatives:** Three Postgres containers (stronger isolation, about 3x the memory), or one database with three schemas (cheap, but makes cross-service joins too easy).
- **Consequence:** A service cannot read another service's tables, because Postgres won't connect a role to a database it has no rights on, so data ownership is enforced by Postgres itself. All three databases share one failure domain, which is acceptable locally. The AWS phase moves the order database to RDS.

## ADR-003: Spring JDBC (`JdbcClient`) instead of JPA/Hibernate

- **Context:** The interview-critical code paths are concurrency SQL: conditional `UPDATE ... WHERE on_hand - reserved >= ?`, `FOR UPDATE SKIP LOCKED`, `INSERT ... ON CONFLICT DO NOTHING` for idempotency.
- **Decision:** Plain SQL through Spring's `JdbcClient`, with schemas managed by Flyway migrations.
- **Alternatives:** Spring Data JPA (less boilerplate for CRUD, but hides the SQL that is the point of the project and adds dirty checking and flush ordering to reason about).
- **Consequence:** More hand-written row mapping. In exchange, every statement the database runs is visible in the code and can be explained line by line.

## ADR-004: Memory budget for the local stack

- **Context:** `docker compose up` must work on an 8 GB machine.
- **Decision:** Every JVM (three services plus Kafka) runs with `-Xmx256m`. Kafka is a single KRaft node (no ZooKeeper) with replication factor 1. Redis is capped at 64 MB with persistence off.
- **Alternatives:** Default heaps (each JVM takes 1/4 of RAM), a 3-broker Kafka cluster.
- **Consequence:** There is no Kafka fault tolerance. That is accepted and listed as a known limitation. The CI smoke job records `docker stats` so the real footprint is measured rather than assumed.

## ADR-005: One Dockerfile, built inside Docker

- **Context:** `docker compose up` should work from a clean checkout without a local JDK.
- **Decision:** A multi-stage root `Dockerfile` with a `SERVICE` build argument. The build stage runs `./mvnw -pl $SERVICE -am package` with a BuildKit cache mount for `~/.m2`. The runtime stage is `eclipse-temurin:21-jre-alpine`, runs as a non-root user, and has a healthcheck on `/actuator/health/readiness`.
- **Alternatives:** Build jars on the host and copy them in (faster, but needs a JDK on the host), or Jib/buildpacks (less transparent).
- **Consequence:** The first build is slow because Maven downloads dependencies. Later builds reuse the cache mount. The runtime image has no JDK or source code.

## ADR-006: One reservation per order, with lines; all-or-nothing in one transaction

- **Context:** An order can contain several SKUs. A partial hold (two of three lines reserved) is useless to the customer and leaks stock.
- **Decision:** `reservations` has one row per order (`order_id UNIQUE`) and a child table `reservation_lines(sku, qty)`. Reserve inserts the header row first, then holds each line in **sku order** inside one transaction. Any line failing throws, which rolls back the header and every line already held.
- **Alternatives:** One reservation row per (order, sku), as in the minimal spec schema. That makes idempotency per line instead of per order, and the order-service would have to compensate partial successes itself.
- **Consequence:** Reserve is idempotent per order: the unique index makes a concurrent duplicate wait for the first transaction, after which it finds the committed row and returns it with 200 instead of 201. A duplicate with different content gets 422. Sorting by sku means two multi-line reservations always lock stock rows in the same order, so they cannot deadlock. Lifecycle transitions (`HELD -> CONFIRMED -> CONSUMED -> RETURNED`, `HELD|CONFIRMED -> RELEASED`, `HELD -> EXPIRED`) are a single `UPDATE ... WHERE status IN (<legal sources>)`. The legal sources and the stock delta for each transition come from one enum (`ReservationStatus`), and a unit test checks every from/to pair.

## ADR-008: Release before reserve writes a tombstone

- **Context:** order-service reserves with a timeout. If the reserve call times out, the saga compensates with a release. But the reserve request may still be in flight and commit *after* the release. Without protection it would then hold stock for an order that is already cancelled, until the 15-minute expiry.
- **Decision:** Releasing an order with no reservation inserts a `RELEASED` row with no lines and a NULL store (a tombstone). A later reserve for that order hits the unique index and returns the tombstone (200, status `RELEASED`) without taking stock. If the tombstone insert loses a race to a reserve that just committed, release retries once and releases the real row.
- **Alternatives:** Ignore releases for unknown orders and rely on the expiry job. That is simpler, but the stock is wrongly unavailable for up to 15 minutes.
- **Consequence:** Release is idempotent in every state: not reserved yet, HELD, CONFIRMED, already RELEASED, or already EXPIRED. EXPIRED counts as a successful release, because the stock is already back. The schema needs `store_id` to be nullable, which is allowed only for RELEASED rows by a CHECK constraint.

## ADR-009: Expiry job with `FOR UPDATE SKIP LOCKED`, batched

- **Context:** HELD reservations older than 15 minutes must give their stock back. The job may run on several instances at once, or overlap its own previous run.
- **Decision:** Each batch is one transaction: a CTE selects up to N due HELD rows `FOR UPDATE SKIP LOCKED` and marks them EXPIRED, then the stock rows are decremented in (store, sku) order. A partial index on `expires_at WHERE status = 'HELD'` keeps the scan cheap. The job drains in batches of 100 every 10 s.
- **Alternatives:** A plain `FOR UPDATE`, where concurrent runs block each other and then find nothing left. A distributed lock or leader election (ShedLock), which is one more moving part. Redis key expiry, which is not transactional with the stock row.
- **Consequence:** Two runs claim disjoint rows, so neither waits nor double-processes. A release racing the job waits on the row lock, re-checks the status, and finds EXPIRED, which makes it a no-op. `ExpiryIT` covers both cases: 4 concurrent drainers over 90 due rows claim exactly 90, and 60 releases raced against the job leave reserved at 0. The staleness bound is one interval plus one batch, so a dead hold can outlive its expiry by about 10 s.

## ADR-007: Reserve with a single conditional UPDATE

- **Context:** Two customers buying the last unit at the same moment must not both succeed. The candidates are (a) one conditional `UPDATE stock SET reserved = reserved + q WHERE ... AND on_hand - reserved >= q`, (b) `SELECT ... FOR UPDATE`, check in Java, then `UPDATE`, and (c) optimistic: read the version, `UPDATE ... WHERE version = v`, retry up to 5 times with jittered backoff.
- **Decision:** (a), the conditional update. (b) and (c) stay in the code behind `inventory.reserve-strategy` so they can be compared.
- **Evidence** (`reports/phase2/reserve-strategies.jsonl`, 2-vCPU Codespace, the load generator shares the box; median of 5 repeats after a discarded warm-up; hot-row workload = 50 keep-alive clients × 40 sequential reservations on one SKU):

  | strategy | throughput req/s (median, range) | p95 ms (median, range) | sell-out 200→50: units sold / 503s |
  |---|---|---|---|
  | conditional | 394.5 (291.6–433.8) | 217.1 (171.4–316.7) | 50 / 0 (all 5 repeats) |
  | for-update | 234.8 (207.1–318.3) | 414.8 (263.9–522.0) | 50 / 0 |
  | optimistic | 202.7 (193.5–269.7) | 410.5 (318.4–456.7) | 50 / 0 |

  All three sold exactly 50 of 50 with 0 oversold in every repeat. The CHECK constraints are a backstop for all of them.
- **Why (a):** The check and the write are one statement and one round trip, so the row lock is held only for that statement. (b) holds the lock across two round trips, which shows up as about 40% lower throughput on one hot row. (c) does the extra read and, under contention, wasted updates.
- **What I expected but did not observe:** optimistic retries running out (HTTP 503). The Hikari pool of 10 caps concurrency at 10 transactions per instance, which kept per-row contention below the 5-attempt limit. With a bigger pool, or several instances, false rejections become possible. `OversellIT` asserts the weaker property that holds for optimistic: it never oversells, and every unsold unit is explained by a request that gave up.
- **Consequence:** One hot SKU serializes on its row lock no matter which strategy is used. That is the scaling limit, and the 100x answer is to split hot stock into buckets or shard by store, not to change the lock type. The measured ranges overlap at the edges (for-update's best repeat beats conditional's worst), so the claim is "faster at the median on this box", not a precise ratio.

## ADR-010: Payment idempotency is the key plus a request hash, with a per-order backstop

- **Context:** order-service retries authorize after a timeout. The retry must never create a second charge, and a key reused for a different charge must be refused rather than silently answered with the old one.
- **Decision:** `Idempotency-Key` is a required header on authorize. The payment row stores the key (`UNIQUE`) and a SHA-256 of `orderId|token|amountCents`. The flow is: look up the key; if found, return it if the hash matches, or reply 422 if it doesn't. Otherwise decide approve/decline, then `INSERT ... ON CONFLICT (idempotency_key) DO NOTHING`. A concurrent duplicate waits on the unique index and then returns the winner's row. A replay returns the original status code (201 or 402) with `Idempotent-Replayed: true`. Separately, a partial unique index `payments(order_id) WHERE status IN ('AUTHORIZED','CAPTURED')` means an order can have only one live authorization, even across different keys.
- **Alternatives:** A separate `idempotency_keys` table that stores the full response body. That suits a generic HTTP layer, and order-service will use it in Phase 4, where the response comes from a saga. Here the payment row *is* the response, so a second table would just be a copy.
- **Consequence:** `PaymentApiIT` checks that 50 concurrent identical authorizes give 1 payment, 1 `AUTHORIZED` event, and exactly one non-replayed response. Capture, void and refund don't need keys: they are status transitions (`UPDATE ... WHERE status IN (<legal sources>)`), so repeating a completed one returns 200 with the current state, and an illegal one returns 409. Only full-amount operations are supported.

## ADR-011: Failure injection is decided up front and an injected timeout fires after commit

- **Context:** Chaos tests need declines, slow responses, timeouts and capture failures that are reproducible run to run.
- **Decision:** `POST /admin/failure-injection {declineRate, timeoutRate, timeoutMs, latencyMs, captureFailureRate, seed}` sets per-instance, in-memory settings. Each call draws its decline/timeout/capture-failure decisions from one seeded `Random`, in a fixed order, before any side effect. With the same seed, a single-threaded driver sees the same sequence every time. An **injected timeout commits the authorization first and then sleeps** before responding. An **injected capture failure returns 503 before any state change.** Tokens beginning `tok_decline` are always declined, so tests can force a decline without randomness.
- **Alternatives:** Toxiproxy or tc netem in front of the service. These are more realistic network faults, but they can't express "the charge committed and only the response was lost", which is the case the saga has to survive.
- **Consequence:** A timeout is the worst case on purpose. The caller can't tell "never charged" from "charged, response lost", so it must retry with the same key, and the gateway returns the existing payment. `PaymentApiIT.injectedTimeoutCommitsThenRetryWithSameKeyGetsSamePayment` proves this: the 500 ms client times out against a 3 s injected delay, the row exists, the retry is a replay, and there is still 1 payment with 1 event. Settings are per instance, so a multi-instance chaos test must configure each instance.

## ADR-012: Cards are opaque tokens, never logged or echoed

- **Decision:** The API accepts only `tok_[A-Za-z0-9_]{8,64}`. A raw card number fails validation (400) and is never stored. The token is excluded from JSON (`@JsonIgnore`) and from `toString`; responses carry only `tokenLast4`. The request hash covers the token without containing it.
- **Consequence:** A simulator can't tokenize anything, so the security property is "no PAN ever reaches this system", and the input validation test checks it.
