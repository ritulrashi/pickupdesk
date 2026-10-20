-- One Postgres instance, one database + owner role per service (ADR-002).
-- Services cannot read each other's tables; tools/reconcile.py gets read-only roles later.
CREATE ROLE "order" LOGIN PASSWORD 'order';
CREATE ROLE inventory LOGIN PASSWORD 'inventory';
CREATE ROLE payment LOGIN PASSWORD 'payment';

CREATE DATABASE orders OWNER "order";
CREATE DATABASE inventory OWNER inventory;
CREATE DATABASE payments OWNER payment;

REVOKE ALL ON DATABASE orders FROM PUBLIC;
REVOKE ALL ON DATABASE inventory FROM PUBLIC;
REVOKE ALL ON DATABASE payments FROM PUBLIC;
