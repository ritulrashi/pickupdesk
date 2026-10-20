-- Stock per (store, sku). The CHECK constraints are the last line of defense against overselling:
-- even if application logic were wrong, Postgres rejects any row where reserved > on_hand.
CREATE TABLE stock (
    store_id   text        NOT NULL,
    sku        text        NOT NULL,
    on_hand    integer     NOT NULL,
    reserved   integer     NOT NULL DEFAULT 0,
    version    bigint      NOT NULL DEFAULT 0,   -- only used by the optimistic strategy (ADR-007)
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, sku),
    CONSTRAINT stock_on_hand_nonneg      CHECK (on_hand >= 0),
    CONSTRAINT stock_reserved_nonneg     CHECK (reserved >= 0),
    CONSTRAINT stock_reserved_le_on_hand CHECK (reserved <= on_hand)
);

-- One reservation per order (order_id UNIQUE makes reserve idempotent).
-- store_id is NULL only for a RELEASED tombstone written when a release arrives before its reserve (ADR-008).
CREATE TABLE reservations (
    id         uuid        PRIMARY KEY,
    order_id   uuid        NOT NULL UNIQUE,
    store_id   text,
    status     text        NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reservations_status_valid
        CHECK (status IN ('HELD', 'CONFIRMED', 'CONSUMED', 'RETURNED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT reservations_store_unless_tombstone
        CHECK (store_id IS NOT NULL OR status = 'RELEASED'),
    CONSTRAINT reservations_held_has_expiry
        CHECK (status <> 'HELD' OR expires_at IS NOT NULL)
);

-- The expiry job only ever scans HELD rows ordered by expiry; a partial index keeps that cheap.
CREATE INDEX reservations_held_by_expiry ON reservations (expires_at) WHERE status = 'HELD';

CREATE TABLE reservation_lines (
    reservation_id uuid    NOT NULL REFERENCES reservations (id),
    sku            text    NOT NULL,
    qty            integer NOT NULL CHECK (qty > 0),
    PRIMARY KEY (reservation_id, sku)
);
