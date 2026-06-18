# legacy-tram sample

Self-contained legacy async MSA sample that demonstrates **B3 trace-id propagation** across
synchronous (HTTP) and asynchronous (Eventuate Tram + Kafka) service boundaries, using
Spring Boot 2.7 / Spring Cloud Sleuth / Java 8.

## Stack diagram

```
 [HTTP client]
      │ POST /orders
      ▼
 ┌──────────┐  sync HTTP  ┌─────────────┐  Eventuate outbox
 │ order-web│────────────►│ reservation │──────────────────►┐
 │ (orderdb)│             │(reservationdb│    (message table) │
 └──────────┘             └─────────────┘                    │
                                                              │ Kafka
                                    ┌─────────────────────────┘
                                    ▼
                          ┌──────────────────────┐  subscribe
                          │ eventuate-cdc-service│──────────►  Kafka topic
                          │  (reads binlog of    │
                          │   message table)     │
                          └──────────────────────┘
                                    │ Kafka
                                    ▼
                          ┌───────────┐
                          │  ledger   │  (received_messages dedup)
                          │ (ledgerdb)│
                          └───────────┘
```

| Service | Role | DB | Eventuate role |
|---|---|---|---|
| `order-web` | HTTP entry, inserts `orders`, sync-calls `reservation` | orderdb | none |
| `reservation` | inserts `reservations`, publishes `OrderReserved` via outbox | reservationdb | publisher |
| `ledger` | subscribes `OrderReserved`, inserts `ledger_entries` | ledgerdb | subscriber |
| `eventuate-cdc-service` | reads reservation binlog → Kafka | — | CDC relay |
| `kafka` | ZooKeeper-mode, single-broker | — | message bus |
| `zookeeper` | required by Eventuate CDC 0.17.0 for leader election | — | coordination |
| `mysql` | MySQL 8.0, binlog ROW | orderdb / reservationdb / ledgerdb | binlog source |

## Prerequisites

- **Docker** 24+ with Compose v2 (`docker compose` command)
- **Builder PR #60** — the graph-rag test-generator builder, if you want to attach the
  SchemaExtractor / capture pipeline (Task 7 and beyond)
- **Task 1 SchemaExtractor** — needed only for builder attach; not required for boot smoke

## Booting the stack

### Standard boot (init.sql schema path)

```bash
cd samples/legacy-tram
docker compose up -d --build --wait order-web reservation ledger eventuate-cdc-service
```

`--wait` blocks until all four services are healthy/started. Build is cached after the first run.

Verify schema bootstrap:

```bash
docker compose exec -T mysql mysql -prootpw -e "SHOW TABLES IN reservationdb;"   # message
docker compose exec -T mysql mysql -prootpw -e "SHOW TABLES IN ledgerdb;"        # received_messages
docker compose exec -T mysql mysql -prootpw -e "SHOW TABLES IN orderdb;"         # orders
```

### E2E overlay (host port publish)

```bash
docker compose -f docker-compose.yml -f docker-compose.e2e.yml \
  up -d --build --wait order-web reservation ledger eventuate-cdc-service
```

Host ports:

| Service | Host port |
|---|---|
| order-web | `localhost:58080` |
| mysql | `localhost:53306` |
| kafka | `localhost:59092` |

Send a test order:

```bash
curl -s -X POST http://localhost:58080/orders -H 'Content-Type: application/json' \
     -d '{"item":"widget-1"}' | jq .
```

### JPA-fallback path (no init.sql)

This path validates that the fallback JPA entities (`EventuateMessageEntity`,
`EventuateReceivedMessagesEntity`) create the Eventuate tables when init.sql is absent:

```bash
docker compose -f docker-compose.yml -f docker-compose.no-initsql.yml \
  up -d --build --wait reservation ledger
docker compose exec -T mysql mysql -prootpw -e "SHOW TABLES IN reservationdb;"   # message
docker compose exec -T mysql mysql -prootpw -e "SHOW TABLES IN ledgerdb;"        # received_messages
```

## Fallback toggle: EVENTUATE_B3_FALLBACK

By default the services use native Eventuate Tram Sleuth integration for B3 trace-id
propagation through the outbox headers.

Set `EVENTUATE_B3_FALLBACK=true` to switch to the manual `B3MessageInterceptor` path
(reads `X-B3-TraceId` from MDC and writes it to the `headers` JSON directly):

```bash
docker compose up -d \
  -e EVENTUATE_B3_FALLBACK=true \
  order-web reservation ledger eventuate-cdc-service
```

The Task 7 E2E test asserts the trace-id appears end-to-end in `ledger_entries.trace_id`
regardless of which path is active.

## Teardown

```bash
docker compose down -v   # removes containers + volumes (clean state for next run)
```

## Pinned versions

See `VERSIONS.md` for the full version matrix (Boot 2.7.18, Tram 0.35.0.RELEASE,
CDC 0.17.0.RELEASE, MySQL 8.0, Kafka/ZooKeeper 7.5.0).

## Notes on Eventuate CDC 0.17.0

CDC 0.17.0.RELEASE requires ZooKeeper for Kafka leadership election (it bundles a
ZooKeeper health check that references the connection string). The brief originally
specified KRaft (no ZooKeeper), but CDC 0.17.0's `ZookeeperHealthCheck` and leadership
election make a live ZK connection mandatory for the health endpoint to return UP. A
ZooKeeper service (`confluentinc/cp-zookeeper:7.5.0`) is therefore included, and
Kafka is configured in ZK-mode accordingly.

Additionally, CDC 0.17.0's `UnifiedCdcConfigurator` validates two extra properties
that must be explicitly provided:
- `EVENTUATE_OUTBOX_ID=1` (property `eventuate.outbox.id`)
- `EVENTUATELOCAL_CDC_READ_OLD_DEBEZIUM_DB_OFFSET_STORAGE_TOPIC=false` (property
  `eventuatelocal.cdc.read.old.debezium.db.offset.storage.topic`)
