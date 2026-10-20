# CLAUDE.md

Full spec: `docs/spec.md`. Design decisions: `docs/decisions.md`.

## Rules (non-negotiable)

- Never invent a metric. Every number in README.md, BENCHMARKS.md, or resume bullets must come from a script in `/bench` or a test report in `/reports`, committed with raw output.
- If a feature is not implemented and tested, it does not appear in the README feature list.
- Build in phases (spec section 7). Do not start phase N+1 until phase N acceptance criteria pass in CI.
- Everything runs locally with `docker compose up` on a machine with 8 GB RAM (single-node Kafka in KRaft mode, small JVM heaps via `-Xmx256m`).
- No Apple names, logos, or trademarks anywhere. Generic SKUs like `PHONE-128-BLK`, store ids like `STORE-0042`.
- Every design decision with a tradeoff gets a short entry in `docs/decisions.md` (ADR style: context, decision, alternatives, consequence).
- Prefer boring, explainable choices over clever ones. The author must be able to defend each in an interview.

## Working notes

- Build: Maven multi-module (`./mvnw -B verify`), Java 21, Spring Boot 3.5.x.
  Unit tests are `*Test` (surefire), Testcontainers integration tests are `*IT` (failsafe, need Docker).
- Money is integer cents everywhere. Never floating point.
- Never log card tokens, JWTs, or pickup codes.
- Benchmarks record the machine (CPU, RAM, docker settings) next to their raw output.
