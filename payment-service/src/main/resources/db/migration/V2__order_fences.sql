-- Written by cancel-order (saga compensation). Once an order is fenced, any authorize for it is declined,
-- so an authorize request delayed in flight cannot create a live charge after the order was cancelled.
CREATE TABLE order_fences (
    order_id   uuid        PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now()
);
