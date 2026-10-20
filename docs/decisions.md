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
