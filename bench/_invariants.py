"""Cross-service consistency checks, run directly against the three Postgres databases (not through the APIs).

Used by duplicate_test.py and chaos_test.py after the system has converged. Every check returns a list of
violations; an empty list means the invariant holds. tools/reconcile.py (Phase 6) generalizes these.
"""

from collections import defaultdict

from _common import psql

UNFINISHED = {"CREATED", "RESERVED", "PAYMENT_AUTHORIZED", "PAYMENT_FAILED", "EXPIRED"}
LIVE_PAYMENT = {"AUTHORIZED", "CAPTURED"}

# What each order status requires of its payment and reservation once the system is quiet.
EXPECT = {
    "CONFIRMED": ({"AUTHORIZED"}, {"CONFIRMED"}),
    "READY_FOR_PICKUP": ({"AUTHORIZED"}, {"CONFIRMED"}),
    "PICKED_UP": ({"CAPTURED"}, {"CONSUMED"}),
    "RETURNED": ({"REFUNDED"}, {"RETURNED"}),
}


def snapshot(customer_ref):
    orders = {r[0]: {"status": r[1], "store": r[2], "reason": r[3]} for r in psql("orders",
        f"SELECT id, status, store_id, coalesce(failure_reason, '') FROM orders WHERE customer_ref = '{customer_ref}'")}
    ids = list(orders)
    payments, reservations, lines = defaultdict(list), {}, defaultdict(list)
    if ids:
        in_list = ",".join(f"'{i}'" for i in ids)
        for oid, status in psql("payments", f"SELECT order_id, status FROM payments WHERE order_id IN ({in_list})"):
            payments[oid].append(status)
        for oid, status in psql("inventory", f"SELECT order_id, status FROM reservations WHERE order_id IN ({in_list})"):
            reservations[oid] = status
        for oid, sku, qty in psql("inventory", f"""
                SELECT r.order_id, l.sku, l.qty FROM reservation_lines l JOIN reservations r ON r.id = l.reservation_id
                WHERE r.order_id IN ({in_list})"""):
            lines[oid].append((sku, int(qty)))
    return orders, payments, reservations, lines


def check_orders(customer_ref):
    orders, payments, reservations, _ = snapshot(customer_ref)
    v = []
    for oid, o in orders.items():
        st, pays, res = o["status"], payments.get(oid, []), reservations.get(oid)
        live = [p for p in pays if p in LIVE_PAYMENT]
        if st in UNFINISHED:
            v.append(f"{oid} still unfinished: {st}")
        if len(live) > 1:
            v.append(f"{oid} has {len(live)} live payments {pays}")   # a double charge
        if st in EXPECT:
            want_pay, want_res = EXPECT[st]
            if sum(1 for p in pays if p in want_pay) != 1:
                v.append(f"{oid} {st}: payments {pays}, expected exactly one of {want_pay}")
            if res not in want_res:
                v.append(f"{oid} {st}: reservation {res}, expected {want_res}")
        if st == "CANCELLED":
            if live:
                v.append(f"{oid} CANCELLED but payment still live {pays}")
            if res in ("HELD", "CONFIRMED"):
                v.append(f"{oid} CANCELLED but reservation still {res}")
    return orders, v


def check_stock(store, initial_on_hand):
    """reserved == units in live holds; on_hand == initial - consumed + returned (per sku)."""
    v = []
    rows = psql("inventory", f"SELECT sku, on_hand, reserved FROM stock WHERE store_id = '{store}'")
    agg = defaultdict(lambda: defaultdict(int))
    for sku, status, qty in psql("inventory", f"""
            SELECT l.sku, r.status, sum(l.qty) FROM reservation_lines l JOIN reservations r ON r.id = l.reservation_id
            WHERE r.store_id = '{store}' GROUP BY l.sku, r.status"""):
        agg[sku][status] = int(qty)
    for sku, on_hand, reserved in rows:
        a = agg[sku]
        held = a["HELD"] + a["CONFIRMED"]
        if int(reserved) != held:
            v.append(f"{store}/{sku} reserved={reserved} but live holds={held}")
        expected = initial_on_hand.get(sku, 0) - a["CONSUMED"]
        if int(on_hand) != expected:
            v.append(f"{store}/{sku} on_hand={on_hand}, expected {expected} (initial - consumed + returned)")
        if int(reserved) > int(on_hand):
            v.append(f"{store}/{sku} oversold: reserved {reserved} > on_hand {on_hand}")
    return v


def check_outbox_and_consumers():
    v = []
    unpublished = int(psql("orders", "SELECT count(*) FROM outbox WHERE published_at IS NULL")[0][0])
    if unpublished:
        v.append(f"{unpublished} outbox rows unpublished")
    # Every PickedUp/Returned event the relay published was applied at most once (dedupe by event id).
    dup = psql("inventory", "SELECT event_id FROM processed_events GROUP BY event_id HAVING count(*) > 1")
    if dup:
        v.append(f"{len(dup)} event ids processed more than once")
    return v
