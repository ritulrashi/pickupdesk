-- Consumer-side dedupe for the at-least-once order-events stream: one row per event id, inserted in the
-- same transaction as the event's effect, so an effect and its "seen" marker commit or roll back together.
CREATE TABLE processed_events (
    event_id     uuid        PRIMARY KEY,
    type         text        NOT NULL,
    order_id     uuid        NOT NULL,
    applied      boolean     NOT NULL,      -- false: duplicate-free but the transition was not legal any more
    processed_at timestamptz NOT NULL DEFAULT now()
);
