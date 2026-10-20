#!/usr/bin/env python3
"""Chaos test (Phase 4 acceptance: kill-the-relay and kill-payment-service on the real stack).

Scenarios (each with its own customer_ref and store, so their invariants are checked in isolation):

  faults        payment-service failure injection on (seeded): declines, timeouts longer than order-service's read
                timeout (the charge commits, the response is lost), capture failures. Orders are created and then
                picked up, with clients retrying on non-final answers.
  kill-payment  orders in flight while payment-service is SIGKILLed and restarted.
  kill-order    orders in flight while order-service (orchestrator + relay) is SIGKILLed and restarted. Clients
                retry with the same keys; recovery finishes or compensates what the crash interrupted.
  kill-relay    a burst of pickups (each writes an OrderPickedUp outbox row) while order-service is SIGKILLed,
                so the relay dies mid-publish. Inventory must consume each pickup exactly once.

After each scenario: wait for convergence, then check _invariants (no unfinished orders, at most one live
payment per order, payment/reservation state matches order state, stock counts match holds and pickups, outbox
drained, no event applied twice).

Usage: python3 bench/chaos_test.py > reports/phase4/chaos-test.jsonl
"""

import argparse
import json
import random
import re
import subprocess
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor

from _common import REPO, Client, machine_info, psql
from _invariants import check_orders, check_outbox_and_consumers, check_stock
from _orders import (ORDER_URL, create_until_final, order_body, reset_faults, seed_store, set_faults,
                     wait_converged)


def compose(*args):
    subprocess.run(["docker", "compose", *args], cwd=REPO, check=True, capture_output=True)


def kill(service):
    compose("kill", "-s", "SIGKILL", service)


def start(service):
    compose("up", "-d", "--wait", service)


def retry_metrics():
    """Resilience4j retry counters for calls from order-service to payment-service (cumulative since start)."""
    status, _, body = Client(ORDER_URL).request("GET", "/actuator/prometheus")
    out = {}
    if status == 200:
        for line in body.decode().splitlines():
            m = re.match(r'resilience4j_retry_calls_total\{.*kind="([a-z_]+)".*name="payment".*\} ([0-9.]+)', line)
            if m:
                out[m.group(1)] = float(m.group(2))
    return out


def act(order_id, action, body=None, deadline_s=90):
    """POST an associate action until it gives a final answer. Returns (status, json)."""
    end = time.time() + deadline_s
    c = Client(ORDER_URL, timeout=15)
    status, payload = None, None
    while time.time() < end:
        status, _, raw = c.request("POST", f"/api/orders/{order_id}/{action}", body)
        try:
            payload = json.loads(raw) if raw else None
        except ValueError:
            payload = None
        if status in (200, 403, 423) or (status == 409 and payload and payload.get("type") != "urn:pickupdesk:in-progress"):
            break
        if status == -1:
            c.close()
            c = Client(ORDER_URL, timeout=15)
        time.sleep(0.5)
    c.close()
    return status, payload


def pickup_code(order_id, from_create):
    if from_create:
        return from_create
    _, _, raw = Client(ORDER_URL).request("POST", f"/api/orders/{order_id}/pickup-code")
    return json.loads(raw)["pickupCode"]


def create_many(customer, store, n, rng, workers=16):
    jobs = [(f"{customer}-{i:04d}", order_body(customer, store, rng)) for i in range(n)]
    with ThreadPoolExecutor(workers) as pool:
        return list(pool.map(lambda j: (j[0],) + create_until_final(j[0], j[1]), jobs))


def pick_up_all(created, workers=16):
    confirmed = [(r[2]["id"], r[2].get("pickupCode")) for r in created
                 if r[1] == 201 and r[2] and r[2].get("status") == "CONFIRMED"]

    def one(item):
        oid, code = item
        act(oid, "ready")
        return act(oid, "pickup", {"pickupCode": pickup_code(oid, code)})[0]

    with ThreadPoolExecutor(workers) as pool:
        codes = list(pool.map(one, confirmed))
    return len(confirmed), codes


def verify(name, customer, store, initial, extra):
    converged = wait_converged(customer)
    orders, violations = check_orders(customer)
    violations += check_stock(store, initial)
    violations += check_outbox_and_consumers()
    statuses = {}
    for o in orders.values():
        key = o["status"] + (":" + o["reason"] if o["reason"] else "")
        statuses[key] = statuses.get(key, 0) + 1
    result = {"type": "scenario", "scenario": name, "customer_ref": customer, "store": store,
              "orders": len(orders), "status_counts": statuses, "converged": converged,
              "violation_count": len(violations), "violations": violations[:30], **extra}
    result["pass"] = converged and not violations
    print(json.dumps(result), flush=True)
    return result["pass"]


def scenario_faults(n, rng):
    customer, store = f"chaos-faults-{uuid.uuid4().hex[:6]}", f"STORE-{rng.randint(1000, 9999)}"
    initial = seed_store(store, 100_000)
    before = retry_metrics()
    set_faults(declineRate=0.1, timeoutRate=0.2, timeoutMs=3000, latencyMs=0, captureFailureRate=0.3, seed=7)
    created = create_many(customer, store, n, rng)
    attempted, pickup_status = pick_up_all(created)
    reset_faults()
    after = retry_metrics()
    ids = ",".join(f"'{r[2]['id']}'" for r in created if r[2] and r[2].get("id")) or "NULL"
    injected_declines = int(psql("payments",
        f"SELECT count(*) FROM payments WHERE decline_reason = 'injected_decline' AND order_id IN ({ids})")[0][0])
    return verify("faults", customer, store, initial, {
        "client_requests": sum(r[3] for r in created),
        "client_non_final_answers_retried": sum(r[4] for r in created),
        "injected_declines": injected_declines,
        "payment_retry_metric_delta": {k: after.get(k, 0) - before.get(k, 0) for k in after},
        "pickups_attempted": attempted,
        "pickups_ok": pickup_status.count(200),
    })


def scenario_kill(n, rng, victim, kill_after_s, down_s):
    customer, store = f"chaos-kill-{victim}-{uuid.uuid4().hex[:6]}", f"STORE-{rng.randint(1000, 9999)}"
    initial = seed_store(store, 100_000)
    holder = {}
    t = threading.Thread(target=lambda: holder.update(created=create_many(customer, store, n, rng)))
    t.start()
    time.sleep(kill_after_s)
    t_kill = time.time()
    kill(victim)
    time.sleep(down_s)
    start(victim)
    restarted_after = round(time.time() - t_kill, 1)
    t.join()
    created = holder["created"]
    return verify(f"kill-{victim}", customer, store, initial, {
        "killed": victim, "killed_after_s": kill_after_s, "down_for_s": restarted_after,
        "client_requests": sum(r[3] for r in created),
        "client_non_final_answers_retried": sum(r[4] for r in created),
    })


def scenario_kill_relay(n, rng):
    customer, store = f"chaos-relay-{uuid.uuid4().hex[:6]}", f"STORE-{rng.randint(1000, 9999)}"
    initial = seed_store(store, 100_000)
    created = create_many(customer, store, n, rng)
    holder = {}
    t = threading.Thread(target=lambda: holder.update(p=pick_up_all(created)))
    t.start()
    time.sleep(2.0)
    kill("order-service")   # relay dies with whatever batch it was publishing
    time.sleep(3)
    start("order-service")
    t.join()
    attempted, pickup_status = holder["p"]
    wait_converged(customer)
    picked = int(psql("orders", f"SELECT count(*) FROM orders WHERE customer_ref = '{customer}' AND status = 'PICKED_UP'")[0][0])
    consumed = int(psql("inventory", f"SELECT count(*) FROM reservations WHERE store_id = '{store}' AND status = 'CONSUMED'")[0][0])
    return verify("kill-relay", customer, store, initial, {
        "pickups_attempted": attempted, "pickups_ok": pickup_status.count(200),
        "orders_picked_up": picked, "reservations_consumed": consumed,
        "picked_equals_consumed": picked == consumed,
    }) and picked == consumed


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--orders", type=int, default=150)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--scenarios", default="faults,kill-payment,kill-order,kill-relay")
    args = ap.parse_args()
    rng = random.Random(args.seed)
    print(json.dumps({"type": "machine", **machine_info(), "params": vars(args)}), flush=True)
    ok = True
    for s in args.scenarios.split(","):
        if s == "faults":
            ok &= scenario_faults(args.orders, rng)
        elif s == "kill-payment":
            ok &= scenario_kill(args.orders, rng, "payment-service", kill_after_s=1.5, down_s=8)
        elif s == "kill-order":
            ok &= scenario_kill(args.orders, rng, "order-service", kill_after_s=1.5, down_s=3)
        elif s == "kill-relay":
            ok &= scenario_kill_relay(args.orders, rng)
    print(json.dumps({"type": "summary", "pass": bool(ok)}), flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
