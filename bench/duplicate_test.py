#!/usr/bin/env python3
"""Duplicate-request test (Phase 4 acceptance: "500 duplicated creates, one order each").

For each of --orders logical orders, the same request (same Idempotency-Key, same body) is sent --copies times:
--concurrent of them at the same instant (a client and its retry racing), the rest sequentially afterwards
(late retries). Then, directly in the three databases, it checks that every key produced exactly one order,
one reservation, and at most one live payment, plus the cross-service invariants in _invariants.py.

Usage: python3 bench/duplicate_test.py > reports/phase4/duplicate-test.jsonl
"""

import argparse
import json
import random
import sys
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor

from _common import machine_info, psql
from _invariants import check_orders, check_outbox_and_consumers, check_stock
from _orders import create_until_final, order_body, seed_store, wait_converged


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--orders", type=int, default=500)
    ap.add_argument("--copies", type=int, default=4)
    ap.add_argument("--concurrent", type=int, default=2)
    ap.add_argument("--workers", type=int, default=32)
    ap.add_argument("--seed", type=int, default=20261020)
    args = ap.parse_args()

    run = uuid.uuid4().hex[:8]
    customer = f"dup-{run}"
    store = f"STORE-{random.Random(run).randint(1000, 9999)}"
    print(json.dumps({"type": "machine", **machine_info(), "params": vars(args), "customer_ref": customer,
                      "store": store}), flush=True)
    initial = seed_store(store, 100_000)
    rng = random.Random(args.seed)
    jobs = [(f"{customer}-{i:04d}", order_body(customer, store, rng)) for i in range(args.orders)]

    results = {}
    lock = threading.Lock()

    def one(job):
        key, body = job
        outcomes = []
        with ThreadPoolExecutor(args.concurrent) as burst:   # copies racing each other
            outcomes += [f.result() for f in [burst.submit(create_until_final, key, body)
                                              for _ in range(args.concurrent)]]
        for _ in range(args.copies - args.concurrent):     # late retries of an already-finished request
            outcomes.append(create_until_final(key, body))
        with lock:
            results[key] = outcomes

    with ThreadPoolExecutor(args.workers) as pool:
        list(pool.map(one, jobs))

    converged = wait_converged(customer)
    orders, violations = check_orders(customer)
    violations += check_stock(store, initial)
    violations += check_outbox_and_consumers()

    per_key = {k: int(n) for k, n in psql("orders", f"""
        SELECT k.key, count(o.id) FROM idempotency_keys k JOIN orders o ON o.id = k.order_id
        WHERE k.key LIKE '{customer}-%' GROUP BY k.key""")}
    keys_with_one_order = sum(1 for k in results if per_key.get(k) == 1)
    ids_seen = {}
    for k, outs in results.items():
        ids = {o[1].get("id") for o in outs if o[1] and o[1].get("id")}
        ids_seen[k] = ids
        if len(ids) != 1:
            violations.append(f"key {k}: responses carried order ids {ids}")
    statuses = {}
    for o in orders.values():
        statuses[o["status"]] = statuses.get(o["status"], 0) + 1

    summary = {
        "type": "summary",
        "logical_orders": args.orders,
        "requests_sent": sum(len(v) for v in results.values()),
        "duplicate_requests": sum(len(v) - 1 for v in results.values()),
        "client_retries_on_in_progress_or_errors": sum(o[3] for v in results.values() for o in v),
        "orders_in_db": len(orders),
        "keys_with_exactly_one_order": keys_with_one_order,
        "order_status_counts": statuses,
        "converged": converged,
        "violations": violations[:50],
        "violation_count": len(violations),
        "pass": converged and not violations and len(orders) == args.orders and keys_with_one_order == args.orders,
    }
    print(json.dumps(summary), flush=True)
    sys.exit(0 if summary["pass"] else 1)


if __name__ == "__main__":
    main()
