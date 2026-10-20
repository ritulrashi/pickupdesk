"""A well-behaved client for the order API: retries with the SAME Idempotency-Key on anything that is not a final
answer (transport error, 5xx, 202 still-processing, 409 in-progress). That is the behavior idempotency exists for.
"""

import json
import random
import time
import uuid

from _common import Client

ORDER_URL = "http://127.0.0.1:8081"
INVENTORY_URL = "http://127.0.0.1:8082"
PAYMENT_URL = "http://127.0.0.1:8083"
SKUS = ["PHONE-128-BLK", "PHONE-256-WHT", "TABLET-64-GRY", "EARBUDS-WHT", "CHARGER-20W", "CASE-CLR"]

FINAL = {201, 402, 409}   # confirmed, declined, out of stock (409 in-progress is distinguished by type)


def seed_store(store, units):
    c = Client(INVENTORY_URL)
    for sku in SKUS:
        status, _, body = c.request("POST", "/admin/stock", {"storeId": store, "sku": sku, "onHand": units})
        if status != 200:
            raise SystemExit(f"seed {store}/{sku} failed: {status} {body!r}")
    c.close()
    return {sku: units for sku in SKUS}


def order_body(customer_ref, store, rng):
    items = rng.sample(SKUS, rng.randint(1, 3))
    return {"customerRef": customer_ref, "storeId": store,
            "items": [{"sku": s, "qty": rng.randint(1, 2)} for s in sorted(items)],
            "paymentToken": "tok_bench_" + uuid.uuid4().hex[:12]}


def create_until_final(key, body, deadline_s=120, client=None):
    """Returns (final_status, body_json, attempts, transient_errors)."""
    own = client is None
    c = client or Client(ORDER_URL, timeout=15)
    attempts = errors = 0
    end = time.time() + deadline_s
    status, payload = None, None
    while time.time() < end:
        attempts += 1
        status, _, raw = c.request("POST", "/api/orders", body, {"Idempotency-Key": key})
        try:
            payload = json.loads(raw) if raw else None
        except ValueError:
            payload = None
        in_progress = status == 409 and payload and payload.get("type") == "urn:pickupdesk:in-progress"
        if status in FINAL and not in_progress:
            break
        if status == 503 and payload and payload.get("status") == "CANCELLED":
            break   # a final answer: cancelled because a downstream was unavailable
        errors += 1
        if status == -1:
            c.close()
            c = Client(ORDER_URL, timeout=15)
        time.sleep(min(2.0, 0.2 * attempts) * random.uniform(0.5, 1.0))
    if own:
        c.close()
    return status, payload, attempts, errors


def wait_converged(customer_ref, timeout_s=180):
    """Waits until no order of this run is unfinished and the outbox is drained."""
    from _common import psql
    end = time.time() + timeout_s
    while time.time() < end:
        unfinished = int(psql("orders", f"""
            SELECT count(*) FROM orders WHERE customer_ref = '{customer_ref}'
              AND status IN ('CREATED','RESERVED','PAYMENT_AUTHORIZED','PAYMENT_FAILED','EXPIRED')""")[0][0])
        unpublished = int(psql("orders", "SELECT count(*) FROM outbox WHERE published_at IS NULL")[0][0])
        if unfinished == 0 and unpublished == 0:
            return True
        time.sleep(2)
    return False


def set_faults(**settings):
    c = Client(PAYMENT_URL)
    status, _, body = c.request("POST", "/admin/failure-injection", settings)
    c.close()
    if status != 200:
        raise SystemExit(f"failure injection failed: {status} {body!r}")


def reset_faults():
    c = Client(PAYMENT_URL)
    c.request("DELETE", "/admin/failure-injection")
    c.close()
