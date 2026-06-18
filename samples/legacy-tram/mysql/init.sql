-- Eventuate Tram legacy-tram sample: schema init
-- Creates 3 app DBs + 1 Eventuate infra DB, populates all tables, and wires up users.
-- Eventuate 0.35.0 / CDC 0.17.0 default to the 'eventuate' schema for infra tables
-- (message, offset_store, cdc_monitoring, received_messages) — we create that schema here.

CREATE DATABASE IF NOT EXISTS orderdb;
CREATE DATABASE IF NOT EXISTS reservationdb;
CREATE DATABASE IF NOT EXISTS ledgerdb;
CREATE DATABASE IF NOT EXISTS eventuate;

-- ── A: orderdb ──────────────────────────────────────────────────────────────
USE orderdb;
CREATE TABLE IF NOT EXISTS orders (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id     VARCHAR(255) NOT NULL,
  amount      INT,
  trace_id    VARCHAR(64),
  created_at  BIGINT
);

-- ── B: reservationdb ────────────────────────────────────────────────────────
USE reservationdb;
CREATE TABLE IF NOT EXISTS reservations (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id   BIGINT NOT NULL,
  user_id    VARCHAR(255),
  amount     INT,
  trace_id   VARCHAR(64),
  created_at BIGINT
);

-- ── C: ledgerdb ─────────────────────────────────────────────────────────────
USE ledgerdb;
CREATE TABLE IF NOT EXISTS ledger_entries (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id    BIGINT NOT NULL UNIQUE,
  user_id     VARCHAR(255),
  amount      INT,
  trace_id    VARCHAR(64),
  created_at  BIGINT
);

-- ── Eventuate infra schema ───────────────────────────────────────────────────
-- All Eventuate Tram 0.35 / CDC 0.17 infra tables live in 'eventuate' by default.
USE eventuate;

-- Outbox (published by reservation service via MessageProducerImpl)
CREATE TABLE IF NOT EXISTS message (
  id                VARCHAR(255) PRIMARY KEY,
  destination       VARCHAR(1000) NOT NULL,
  headers           LONGTEXT NOT NULL,
  payload           LONGTEXT NOT NULL,
  published         SMALLINT DEFAULT 0,
  message_partition SMALLINT,
  creation_time     BIGINT
);

-- CDC offset store (written by eventuate-cdc-service)
CREATE TABLE IF NOT EXISTS offset_store (
  client_name       VARCHAR(255) PRIMARY KEY,
  serialized_offset VARCHAR(255)
);

-- CDC heartbeat monitoring (written by eventuate-cdc-service)
CREATE TABLE IF NOT EXISTS cdc_monitoring (
  reader_id VARCHAR(255) PRIMARY KEY,
  last_time BIGINT
);

-- Dedup table (consumed by ledger service via MessageConsumer)
CREATE TABLE IF NOT EXISTS received_messages (
  consumer_id   VARCHAR(255) NOT NULL,
  message_id    VARCHAR(255) NOT NULL,
  creation_time BIGINT,
  published     SMALLINT DEFAULT 0,
  PRIMARY KEY (consumer_id, message_id)
);

-- ── Users & grants ───────────────────────────────────────────────────────────
-- App user: owns order-web (orderdb), reservation (reservationdb), ledger (ledgerdb).
-- Also needs eventuate.message INSERT (reservation) and eventuate.received_messages DML (ledger).
CREATE USER IF NOT EXISTS 'app'@'%' IDENTIFIED WITH mysql_native_password BY 'apppw';
GRANT ALL PRIVILEGES ON orderdb.*      TO 'app'@'%';
GRANT ALL PRIVILEGES ON reservationdb.* TO 'app'@'%';
GRANT ALL PRIVILEGES ON ledgerdb.*     TO 'app'@'%';
GRANT ALL PRIVILEGES ON eventuate.*    TO 'app'@'%';

-- CDC user: binlog reader + Eventuate infra DML (offset_store/cdc_monitoring writes).
CREATE USER IF NOT EXISTS 'cdc'@'%' IDENTIFIED WITH mysql_native_password BY 'cdcpw';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'cdc'@'%';
GRANT ALL PRIVILEGES ON eventuate.*    TO 'cdc'@'%';
GRANT ALL PRIVILEGES ON reservationdb.* TO 'cdc'@'%';

FLUSH PRIVILEGES;
