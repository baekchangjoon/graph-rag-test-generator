-- Eventuate Tram legacy-tram sample: schema init
-- Creates 3 app DBs, Eventuate outbox/dedup/CDC-meta tables, app & CDC users/grants.
-- Column types aligned with fallback JPA entities (EventuateMessageEntity / EventuateReceivedMessagesEntity).

CREATE DATABASE IF NOT EXISTS orderdb;
CREATE DATABASE IF NOT EXISTS reservationdb;
CREATE DATABASE IF NOT EXISTS ledgerdb;

-- ── A: orderdb ──────────────────────────────────────────────────────────────
USE orderdb;
CREATE TABLE IF NOT EXISTS orders (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  item        VARCHAR(255) NOT NULL,
  trace_id    VARCHAR(64),
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ── B: reservationdb ────────────────────────────────────────────────────────
USE reservationdb;

-- Eventuate Tram outbox (published by reservation service)
-- Columns match EventuateMessageEntity (JPA fallback) exactly
CREATE TABLE IF NOT EXISTS message (
  id                VARCHAR(255) PRIMARY KEY,
  destination       VARCHAR(1000) NOT NULL,
  headers           LONGTEXT NOT NULL,
  payload           LONGTEXT NOT NULL,
  published         SMALLINT DEFAULT 0,
  message_partition SMALLINT,
  creation_time     BIGINT
);

-- Eventuate CDC meta: offset store
CREATE TABLE IF NOT EXISTS offset_store (
  client_name       VARCHAR(255) PRIMARY KEY,
  serialized_offset VARCHAR(255)
);

-- Eventuate CDC meta: heartbeat monitoring
CREATE TABLE IF NOT EXISTS cdc_monitoring (
  reader_id VARCHAR(255) PRIMARY KEY,
  last_time BIGINT
);

CREATE TABLE IF NOT EXISTS reservations (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id   BIGINT NOT NULL,
  trace_id   VARCHAR(64),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ── C: ledgerdb ─────────────────────────────────────────────────────────────
USE ledgerdb;

-- Eventuate Tram dedup (consumed by ledger service)
-- Columns match EventuateReceivedMessagesEntity (JPA fallback) exactly
CREATE TABLE IF NOT EXISTS received_messages (
  consumer_id   VARCHAR(255) NOT NULL,
  message_id    VARCHAR(255) NOT NULL,
  creation_time BIGINT,
  published     SMALLINT DEFAULT 0,
  PRIMARY KEY (consumer_id, message_id)
);

CREATE TABLE IF NOT EXISTS ledger_entries (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id    BIGINT NOT NULL,
  trace_id    VARCHAR(64),
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ── Users & grants ───────────────────────────────────────────────────────────
-- App user: accessed by all 3 services (SPRING_DATASOURCE_USERNAME=app)
CREATE USER IF NOT EXISTS 'app'@'%' IDENTIFIED WITH mysql_native_password BY 'apppw';
GRANT ALL PRIVILEGES ON orderdb.*      TO 'app'@'%';
GRANT ALL PRIVILEGES ON reservationdb.* TO 'app'@'%';
GRANT ALL PRIVILEGES ON ledgerdb.*     TO 'app'@'%';

-- CDC user: binlog read + DML on reservationdb (offset_store/cdc_monitoring writes)
CREATE USER IF NOT EXISTS 'cdc'@'%' IDENTIFIED WITH mysql_native_password BY 'cdcpw';
GRANT SELECT, REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'cdc'@'%';
GRANT ALL PRIVILEGES ON reservationdb.* TO 'cdc'@'%';

FLUSH PRIVILEGES;
