#!/usr/bin/env python3
"""Mutation check: break one safety mechanism at a time and confirm the named tests catch it.

A test suite that still passes after a guard is deleted is not testing that guard. For each mutant this
script edits the source, runs only the relevant integration tests, expects them to FAIL, and restores
the file with git checkout. Needs Docker (Testcontainers) and a clean working tree for the mutated files.

Usage: python3 scripts/mutation_check.py [phase] > reports/<phase>/mutation-check.txt
Exit code 1 if any mutant survived.
"""

import os
import subprocess
import sys
import time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
INV = "inventory-service/src/main/java/pickupdesk/inventory/"

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
]


def run(cmd):
    return subprocess.run(cmd, cwd=REPO, capture_output=True, text=True)


def main():
    phase = sys.argv[1] if len(sys.argv) > 1 else "phase2"
    mutants = [m for m in MUTANTS if m[0] == phase]
    commit = run(["git", "rev-parse", "--short", "HEAD"]).stdout.strip()
    print(f"# mutation check {phase} at {commit}")
    print(f"{'id':6} {'result':9} {'secs':>5}  description  [tests]")
    survived = 0
    for _, mid, desc, module, path, old, new, tests in mutants:
        full = os.path.join(REPO, path)
        src = open(full, encoding="utf-8").read()
        if src.count(old) != 1:
            print(f"{mid:6} {'STALE':9} {0:5}  {desc}  (pattern not found exactly once)")
            survived += 1
            continue
        open(full, "w", encoding="utf-8", newline="\n").write(src.replace(old, new))
        t0 = time.time()
        try:
            r = run(["./mvnw", "-B", "-q", "-pl", module, "verify",
                     "-Dtest=NONE", "-Dsurefire.failIfNoSpecifiedTests=false", f"-Dit.test={tests}"])
        finally:
            run(["git", "checkout", "--", path])
        killed = r.returncode != 0
        compiled = "COMPILATION ERROR" not in r.stdout
        result = "KILLED" if killed and compiled else ("NOCOMPILE" if not compiled else "SURVIVED")
        survived += result != "KILLED"
        print(f"{mid:6} {result:9} {int(time.time() - t0):5}  {desc}  [{tests}]", flush=True)
    print(f"# {len(mutants) - survived}/{len(mutants)} mutants killed")
    sys.exit(1 if survived else 0)


if __name__ == "__main__":
    main()
