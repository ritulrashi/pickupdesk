#!/usr/bin/env python3
"""Mutation check: break one safety mechanism at a time and confirm the named tests catch it.

A test suite that still passes after a guard is deleted is not testing that guard. For each mutant this
script edits the source, runs only the relevant integration tests, expects them to FAIL, and restores
the file with git checkout. Needs Docker (Testcontainers) and a clean working tree for the mutated files.

Usage: python3 scripts/mutation_check.py [phase] > reports/<phase>/mutation-check.txt
Exit code 1 if any mutant survived.
"""

import os
import re
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INV = "inventory-service/src/main/java/pickupdesk/inventory/"
PAY = "payment-service/src/main/java/pickupdesk/payment/"
ORD = "order-service/src/main/java/pickupdesk/order/"

# (phase, id, description, module, file, old, new, tests)
MUTANTS = [
    ("phase2", "P2-1", "conditional reserve without the availability guard", "inventory-service",
     INV + "reservation/strategy/ConditionalUpdateReserver.java",
     "AND on_hand - reserved >= :qty", "AND :qty = :qty",
     "OversellIT"),
    ("phase2", "P2-2", "SELECT ... FOR UPDATE without FOR UPDATE (read-then-write race)", "inventory-service",
     INV + "reservation/strategy/SelectForUpdateReserver.java",
     "                FOR UPDATE\n", "",
     "OversellIT"),
    ("phase2", "P2-3", "optimistic update without the version check", "inventory-service",
     INV + "reservation/strategy/OptimisticReserver.java",
     "AND version = :version", "AND :version = :version",
     "OversellIT"),
    ("phase2", "P2-4", "reserve lines not sorted by sku (lock-order deadlock)", "inventory-service",
     INV + "reservation/ReservationService.java",
     ".sorted(Comparator.comparing(ReserveRequest.Line::sku))", "",
     "OversellIT"),
    ("phase2", "P2-5", "reserve insert without ON CONFLICT (idempotency)", "inventory-service",
     INV + "reservation/ReservationRepository.java",
     "VALUES (:id, :orderId, :store, 'HELD', now() + make_interval(secs => :ttl))\n                ON CONFLICT (order_id) DO NOTHING",
     "VALUES (:id, :orderId, :store, 'HELD', now() + make_interval(secs => :ttl))",
     "ReservationApiIT"),
    ("phase2", "P2-6", "transition ignores the legal-source status check", "inventory-service",
     INV + "reservation/ReservationRepository.java",
     "WHERE order_id = :orderId AND status IN (:sources)", "WHERE order_id = :orderId AND (status IN (:sources) OR true)",
     "ReservationApiIT,ExpiryIT"),
    ("phase2", "P2-7", "expiry job claims non-HELD rows", "inventory-service",
     INV + "reservation/ReservationRepository.java",
     "WHERE status = 'HELD' AND expires_at <= now()", "WHERE expires_at <= now() OR status = 'CONFIRMED'",
     "ExpiryIT"),
    ("phase2", "P2-8", "release before reserve writes no tombstone", "inventory-service",
     INV + "reservation/ReservationService.java",
     "if (reservations.insertTombstone(UUID.randomUUID(), orderId)) {",
     "if (true) { return new Reservation(null, orderId, null, ReservationStatus.RELEASED, null, java.util.List.of()); } if (false) {",
     "ReservationApiIT"),
    ("phase2", "P2-9", "releasing an EXPIRED reservation is treated as illegal", "inventory-service",
     INV + "reservation/ReservationService.java",
     "if (current.get().status() == ReservationStatus.EXPIRED) {", "if (false) {",
     "ExpiryIT"),

    ("phase3", "P3-1", "authorize insert without ON CONFLICT on the idempotency key", "payment-service",
     PAY + "PaymentRepository.java",
     "                ON CONFLICT (idempotency_key) DO NOTHING\n", "",
     "PaymentApiIT"),
    ("phase3", "P3-2", "payment transition ignores the legal-source status check", "payment-service",
     PAY + "PaymentRepository.java",
     "WHERE id = :id AND status IN (:sources)", "WHERE id = :id AND (status IN (:sources) OR true)",
     "PaymentApiIT"),
    ("phase3", "P3-3", "authorize without the per-order advisory lock (fence race)", "payment-service",
     PAY + "PaymentService.java",
     "                payments.lockOrder(req.orderId());\n", "",
     "PaymentApiIT"),

    ("phase4", "P4-1", "order transition ignores the legal-source status check", "order-service",
     ORD + "domain/OrderRepository.java",
     "WHERE id = :id AND status IN (:sources)", "WHERE id = :id AND (status IN (:sources) OR true)",
     "OrderTransitionsIT"),
    ("phase4", "P4-2", "state change commits without its outbox row (dual-write gap)", "order-service",
     ORD + "domain/OrderTransitions.java",
     "        outbox.append(eventId, o.id(), type, body);\n", "",
     "OrderSagaIT,OutboxRelayIT,PickupFlowIT"),
    ("phase4", "P4-3", "authorize key not derived from the order (retries would double-charge)", "order-service",
     ORD + "clients/PaymentClient.java",
     'return "order-" + orderId + "-authorize";', 'return "order-" + UUID.randomUUID() + "-authorize";',
     "OrderSagaIT"),
    ("phase4", "P4-4", "compensation forgets to void/fence at payment", "order-service",
     ORD + "saga/SagaOrchestrator.java",
     "                payment.cancelOrder(o.id());\n", "",
     "OrderSagaIT"),
    ("phase4", "P4-5", "order idempotency claim without ON CONFLICT", "order-service",
     ORD + "idempotency/IdempotencyRepository.java",
     "                ON CONFLICT (key) DO NOTHING\n", "",
     "OrderIdempotencyIT"),
    ("phase4", "P4-6", "lease always granted (two actors drive one order)", "order-service",
     ORD + "domain/OrderRepository.java",
     "WHERE id = :id AND (saga_lease_until IS NULL OR saga_lease_until < now())", "WHERE id = :id",
     "OrderIdempotencyIT,PickupFlowIT"),
]


def run(cmd):
    return subprocess.run(cmd, cwd=REPO, capture_output=True, text=True)


SUMMARY = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$", re.M)


def run_its(module, tests):
    """Runs the named failsafe ITs. Returns (tests_run, failed) from the last summary line, or None if no tests ran."""
    r = run(["./mvnw", "-B", "-pl", module, "verify",
             "-Dtest=NONE", "-Dsurefire.failIfNoSpecifiedTests=false", f"-Dit.test={tests}"])
    m = SUMMARY.findall(r.stdout)
    if not m:
        return None, r.stdout[-2000:]
    ran, failures, errors, _ = map(int, m[-1])
    return (ran, failures + errors), r.stdout[-2000:]


def main():
    phase = sys.argv[1] if len(sys.argv) > 1 else "phase2"
    mutants = [m for m in MUTANTS if m[0] == phase or phase == "all"]
    commit = run(["git", "rev-parse", "--short", "HEAD"]).stdout.strip()
    print(f"# mutation check {phase} at {commit}")

    # Baseline: the unmutated code must pass the same test selection, or a "kill" proves nothing.
    for module, tests in sorted({(m[3], m[7]) for m in mutants}):
        result, tail = run_its(module, tests)
        if result is None or result[1] != 0:
            print(f"# BASELINE FAILED for {module} [{tests}]: {result}")
            print(tail)
            sys.exit(2)
        print(f"# baseline {module} [{tests}]: {result[0]} tests, 0 failed")

    print(f"{'id':6} {'result':9} {'secs':>5}  description  [tests]")
    survived = 0
    for _, mid, desc, module, path, old, new, tests in mutants:
        full = os.path.join(REPO, path)
        src = open(full, encoding="utf-8").read()
        if src.count(old) != 1:
            print(f"{mid:6} {'STALE':9} {0:5}  {desc}  (pattern not found exactly once)")
            survived += 1
            continue
        with open(full, "w", encoding="utf-8", newline="") as f:
            f.write(src.replace(old, new))
        t0 = time.time()
        try:
            result, tail = run_its(module, tests)
        finally:
            run(["git", "checkout", "--", path])
        if result is None:
            label = "NOCOMPILE"          # tests never ran: not a kill
        elif result[1] > 0:
            label = "KILLED"
        else:
            label = "SURVIVED"
        survived += label != "KILLED"
        detail = f"{result[1]}/{result[0]} failed" if result else "no tests ran"
        print(f"{mid:6} {label:9} {int(time.time() - t0):5}  {desc}  [{tests}] {detail}", flush=True)
    print(f"# {len(mutants) - survived}/{len(mutants)} mutants killed")
    sys.exit(1 if survived else 0)


if __name__ == "__main__":
    main()
