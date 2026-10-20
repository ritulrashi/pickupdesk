-- Simulated gateway. Money is integer cents (bigint). Cards are represented only by opaque tokens.
CREATE TABLE payments (
    id              uuid        PRIMARY KEY,
    order_id        uuid        NOT NULL,
    token           text        NOT NULL,
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    status          text        NOT NULL
        CHECK (status IN ('AUTHORIZED', 'DECLINED', 'CAPTURED', 'VOIDED', 'REFUNDED')),
    decline_reason  text,
    idempotency_key text        NOT NULL UNIQUE,
    request_hash    text        NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

-- Defense in depth against a double charge: even with two different idempotency keys, an order can have
-- at most one live (authorized or captured) payment. Declined/voided/refunded attempts don't count.
CREATE UNIQUE INDEX payments_one_live_per_order ON payments (order_id)
    WHERE status IN ('AUTHORIZED', 'CAPTURED');

CREATE INDEX payments_by_order ON payments (order_id);

-- Append-only audit trail: one row per state the payment entered.
CREATE TABLE payment_events (
    id         bigserial   PRIMARY KEY,
    payment_id uuid        NOT NULL REFERENCES payments (id),
    type       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX payment_events_by_payment ON payment_events (payment_id);
