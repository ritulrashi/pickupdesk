#!/usr/bin/env python3
"""Oversell test (Phase 2 acceptance).

Fires N concurrent single-unit reservations, released together by a barrier, at one SKU holding --stock units,
then checks the result in Postgres directly (not through the service). A run passes only if:
  * exactly --stock requests got 201 and the rest got 409 (no 5xx, no transport errors),
  * stock.on_hand is unchanged and stock.reserved == number of 201s,
  * reserved units in reservation_lines equal stock.reserved,
  * oversold == 0, where oversold = max(0, reserved - on_hand).

Requires the compose stack: docker compose up -d --wait
Usage: python3 bench/oversell_test.py --concurrency 1000 --runs 20 > reports/phase2/oversell-c1000.jsonl
Output: one JSON object per line (machine info, one line per run, summary). Exit code 1 if any run failed.
"""

import argparse
import json
import sys
import threading
import uuid

from _common import Client, machine_info, percentile, psql

STORE = "STORE-0042"


def one_run(base_url, concurrency, stock, run_tag):
    sku = f"OVERSELL-{run_tag}"
    seed = Client(base_url)
    status, _, body = seed.request("POST", "/admin/stock", {"storeId": STORE, "sku": sku, "onHand": stock})
    seed.close()
    if status != 200:
        raise SystemExit(f"seeding failed: {status} {body!r}")

    barrier = threading.Barrier(concurrency)
    results = [None] * concurrency

    def worker(i):
        c = Client(base_url)
        req = {"orderId": str(uuid.uuid4()), "storeId": STORE, "lines": [{"sku": sku, "qty": 1}]}
        barrier.wait()
        results[i] = c.request("POST", "/internal/reservations", req)[:2]
        c.close()

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(concurrency)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    codes = [r[0] for r in results]
    latencies = [r[1] for r in results]
    created, rejected = codes.count(201), codes.count(409)
    other = concurrency - created - rejected

    on_hand, reserved = map(int, psql("inventory",
        f"SELECT on_hand, reserved FROM stock WHERE store_id = '{STORE}' AND sku = '{sku}'")[0])
    held_units, held_rows = map(int, psql("inventory", f"""
        SELECT coalesce(sum(l.qty), 0), count(*) FROM reservation_lines l
        JOIN reservations r ON r.id = l.reservation_id
        WHERE r.store_id = '{STORE}' AND l.sku = '{sku}' AND r.status IN ('HELD', 'CONFIRMED')""")[0])

    oversold = max(0, reserved - on_hand)
    passed = (created == stock and rejected == concurrency - stock and other == 0
              and on_hand == stock and reserved == created and held_units == reserved and oversold == 0)
    return {
        "type": "run", "sku": sku, "concurrency": concurrency, "stock": stock,
        "http_201": created, "http_409": rejected, "http_other": other,
        "db_on_hand": on_hand, "db_reserved": reserved, "db_held_units": held_units, "db_held_rows": held_rows,
        "oversold": oversold,
        "latency_ms_p50": round(percentile(latencies, 50), 1),
        "latency_ms_p95": round(percentile(latencies, 95), 1),
        "latency_ms_max": round(max(latencies), 1),
        "pass": passed,
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default="http://127.0.0.1:8082")
    ap.add_argument("--concurrency", type=int, default=200)
    ap.add_argument("--stock", type=int, default=50)
    ap.add_argument("--runs", type=int, default=20)
    args = ap.parse_args()

    strategy = json.loads(Client(args.base_url).request("GET", "/actuator/info")[2] or b"{}").get("reserveStrategy")
    print(json.dumps({"type": "machine", **machine_info(), "reserve_strategy": strategy}), flush=True)
    tag = uuid.uuid4().hex[:6].upper()
    runs = []
    for i in range(args.runs):
        r = one_run(args.base_url, args.concurrency, args.stock, f"{tag}-C{args.concurrency}-{i:02d}")
        runs.append(r)
        print(json.dumps(r), flush=True)

    summary = {
        "type": "summary", "concurrency": args.concurrency, "stock": args.stock, "runs": len(runs),
        "runs_passed": sum(r["pass"] for r in runs),
        "total_requests": sum(r["concurrency"] for r in runs),
        "total_oversold": sum(r["oversold"] for r in runs),
        "max_oversold_in_a_run": max(r["oversold"] for r in runs),
        "http_other_total": sum(r["http_other"] for r in runs),
    }
    print(json.dumps(summary), flush=True)
    sys.exit(0 if summary["runs_passed"] == len(runs) else 1)


if __name__ == "__main__":
    main()
