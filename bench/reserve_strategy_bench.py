#!/usr/bin/env python3
"""Compare the three reserve strategies (ADR-007) on the real stack.

For each strategy, inventory-service is recreated with RESERVE_STRATEGY=<strategy> (verified via /actuator/info),
then two workloads run, each --repeats times after one warm-up pass that is discarded:

  hot-row:  --clients threads, each with its own keep-alive connection, send --per-client sequential
            single-unit reservations to ONE sku with plenty of stock. Measures throughput and latency
            when every request contends for the same row.
  sell-out: --sellout-concurrency simultaneous requests against --sellout-stock units (the oversell shape).
            Measures how many units actually sell. A locking strategy sells all of them; optimistic may give
            up early (HTTP 503) and leave units unsold.

Usage: python3 bench/reserve_strategy_bench.py > reports/phase2/reserve-strategies.jsonl
Output: JSON lines (machine info, one line per measured repeat, one summary line per strategy with medians).
"""

import argparse
import json
import os
import subprocess
import threading
import time
import uuid

from _common import REPO, Client, machine_info, median, percentile, psql

STORE = "STORE-0042"
STRATEGIES = ["conditional", "for-update", "optimistic"]


def use_strategy(strategy, base_url):
    env = {**os.environ, "RESERVE_STRATEGY": strategy}
    subprocess.run(["docker", "compose", "up", "-d", "--wait", "inventory-service"],
                   cwd=REPO, env=env, check=True, capture_output=True)
    for _ in range(60):
        status, _, body = Client(base_url).request("GET", "/actuator/info")
        if status == 200:
            actual = json.loads(body).get("reserveStrategy")
            if actual != strategy:
                raise SystemExit(f"expected strategy {strategy}, service reports {actual}")
            return
        time.sleep(1)
    raise SystemExit("inventory-service did not come back")


def seed(base_url, sku, units):
    status, _, body = Client(base_url).request("POST", "/admin/stock", {"storeId": STORE, "sku": sku, "onHand": units})
    if status != 200:
        raise SystemExit(f"seed failed {status} {body!r}")


def hot_row(base_url, clients, per_client):
    sku = "HOT-" + uuid.uuid4().hex[:8].upper()
    seed(base_url, sku, clients * per_client * 10)
    barrier = threading.Barrier(clients + 1)
    results = [[] for _ in range(clients)]

    def worker(i):
        c = Client(base_url)
        c.request("GET", "/actuator/health")  # open the connection before the clock starts
        barrier.wait()
        for _ in range(per_client):
            req = {"orderId": str(uuid.uuid4()), "storeId": STORE, "lines": [{"sku": sku, "qty": 1}]}
            results[i].append(c.request("POST", "/internal/reservations", req)[:2])
        c.close()

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(clients)]
    for t in threads:
        t.start()
    barrier.wait()
    t0 = time.perf_counter()
    for t in threads:
        t.join()
    wall = time.perf_counter() - t0

    flat = [r for rs in results for r in rs]
    lat = [ms for code, ms in flat if code == 201]
    return {
        "requests": len(flat),
        "http_201": sum(1 for code, _ in flat if code == 201),
        "http_503": sum(1 for code, _ in flat if code == 503),
        "http_other": sum(1 for code, _ in flat if code not in (201, 503)),
        "wall_s": round(wall, 3),
        "throughput_rps": round(len(flat) / wall, 1),
        "p50_ms": round(percentile(lat, 50), 2),
        "p95_ms": round(percentile(lat, 95), 2),
        "p99_ms": round(percentile(lat, 99), 2),
    }


def sell_out(base_url, concurrency, stock):
    sku = "SELLOUT-" + uuid.uuid4().hex[:8].upper()
    seed(base_url, sku, stock)
    barrier = threading.Barrier(concurrency)
    codes = [None] * concurrency

    def worker(i):
        c = Client(base_url)
        req = {"orderId": str(uuid.uuid4()), "storeId": STORE, "lines": [{"sku": sku, "qty": 1}]}
        barrier.wait()
        codes[i] = c.request("POST", "/internal/reservations", req)[0]
        c.close()

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(concurrency)]
    t0 = time.perf_counter()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    wall = time.perf_counter() - t0
    on_hand, reserved = map(int, psql("inventory",
        f"SELECT on_hand, reserved FROM stock WHERE store_id = '{STORE}' AND sku = '{sku}'")[0])
    return {
        "http_201": codes.count(201), "http_409": codes.count(409), "http_503": codes.count(503),
        "http_other": concurrency - codes.count(201) - codes.count(409) - codes.count(503),
        "units_unsold": on_hand - reserved, "oversold": max(0, reserved - on_hand), "wall_s": round(wall, 3),
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default="http://127.0.0.1:8082")
    ap.add_argument("--clients", type=int, default=50)
    ap.add_argument("--per-client", type=int, default=40)
    ap.add_argument("--sellout-concurrency", type=int, default=200)
    ap.add_argument("--sellout-stock", type=int, default=50)
    ap.add_argument("--repeats", type=int, default=5)
    ap.add_argument("--strategies", default=",".join(STRATEGIES))
    args = ap.parse_args()

    print(json.dumps({"type": "machine", **machine_info(), "params": vars(args)}), flush=True)
    for strategy in args.strategies.split(","):
        use_strategy(strategy, args.base_url)
        hot_row(args.base_url, args.clients, max(5, args.per_client // 4))      # warm-up, discarded
        sell_out(args.base_url, args.sellout_concurrency, args.sellout_stock)   # warm-up, discarded
        hots, sells = [], []
        for i in range(args.repeats):
            h = hot_row(args.base_url, args.clients, args.per_client)
            s = sell_out(args.base_url, args.sellout_concurrency, args.sellout_stock)
            hots.append(h)
            sells.append(s)
            print(json.dumps({"type": "repeat", "strategy": strategy, "repeat": i, "hot_row": h, "sell_out": s}),
                  flush=True)
        print(json.dumps({
            "type": "summary", "strategy": strategy, "repeats": args.repeats,
            "hot_row_median": {k: median([h[k] for h in hots])
                               for k in ("throughput_rps", "p50_ms", "p95_ms", "p99_ms", "http_503", "http_other")},
            "sell_out_median": {k: median([s[k] for s in sells])
                                for k in ("http_201", "http_503", "units_unsold", "wall_s")},
            "sell_out_oversold_total": sum(s["oversold"] for s in sells),
        }), flush=True)
    use_strategy("conditional", args.base_url)


if __name__ == "__main__":
    main()
