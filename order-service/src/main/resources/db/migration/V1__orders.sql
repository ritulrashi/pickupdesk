-- Prices live server-side; clients never send a price.
CREATE TABLE catalog (
    sku              text   PRIMARY KEY,
    name             text   NOT NULL,
    unit_price_cents bigint NOT NULL CHECK (unit_price_cents > 0)
);
INSERT INTO catalog (sku, name, unit_price_cents) VALUES
    ('PHONE-128-BLK', 'Phone 128 GB, black', 79900),
    ('PHONE-256-WHT', 'Phone 256 GB, white', 89900),
    ('TABLET-64-GRY', 'Tablet 64 GB, gray', 32900),
    ('EARBUDS-WHT',   'Wireless earbuds, white', 12900),
    ('CHARGER-20W',   '20 W USB-C charger', 1900),
    ('CASE-CLR',      'Clear phone case', 2900);

CREATE TABLE orders (
    id                uuid        PRIMARY KEY,
    customer_ref      text        NOT NULL,
    store_id          text        NOT NULL,
    status            text        NOT NULL CHECK (status IN (
                          'CREATED', 'RESERVED', 'PAYMENT_AUTHORIZED', 'CONFIRMED', 'READY_FOR_PICKUP',
                          'PICKED_UP', 'RETURNED', 'PAYMENT_FAILED', 'EXPIRED', 'CANCELLED')),
    total_cents       bigint      NOT NULL CHECK (total_cents > 0),
    payment_token     text        NOT NULL,          -- opaque gateway token, needed to resume the saga
    payment_id        uuid,
    pickup_code_hash  text,
    pickup_attempts   integer     NOT NULL DEFAULT 0,
    capture_failures  integer     NOT NULL DEFAULT 0,
    failure_reason    text,
    saga_lease_until  timestamptz,                   -- who may run this order's saga right now (ADR-014)
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0
);
CREATE INDEX orders_pickup_queue ON orders (store_id, created_at) WHERE status IN ('CONFIRMED', 'READY_FOR_PICKUP');
CREATE INDEX orders_unfinished ON orders (updated_at)
    WHERE status IN ('CREATED', 'RESERVED', 'PAYMENT_AUTHORIZED', 'PAYMENT_FAILED', 'EXPIRED');

CREATE TABLE order_items (
    order_id         uuid    NOT NULL REFERENCES orders (id),
    sku              text    NOT NULL,
    qty              integer NOT NULL CHECK (qty > 0),
    unit_price_cents bigint  NOT NULL CHECK (unit_price_cents > 0),
    PRIMARY KEY (order_id, sku)
);

-- Append-only audit trail of every state change. A trigger makes UPDATE and DELETE fail.
CREATE TABLE order_events (
    id          bigserial   PRIMARY KEY,
    order_id    uuid        NOT NULL REFERENCES orders (id),
    type        text        NOT NULL,
    from_status text,
    to_status   text        NOT NULL,
    payload     jsonb       NOT NULL DEFAULT '{}',
    created_at  timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX order_events_by_order ON order_events (order_id, id);

CREATE FUNCTION order_events_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'order_events is append-only';
END $$;
CREATE TRIGGER order_events_no_update_delete BEFORE UPDATE OR DELETE ON order_events
    FOR EACH ROW EXECUTE FUNCTION order_events_append_only();

-- Transactional outbox: written in the same transaction as the state change, published by the relay.
CREATE TABLE outbox (
    id           uuid        PRIMARY KEY,
    aggregate_id uuid        NOT NULL,
    type         text        NOT NULL,
    payload      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX outbox_unpublished ON outbox (created_at) WHERE published_at IS NULL;

CREATE TABLE idempotency_keys (
    key             text        PRIMARY KEY,
    request_hash    text        NOT NULL,
    order_id        uuid        NOT NULL,
    status          text        NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status integer,
    response_body   jsonb,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);
