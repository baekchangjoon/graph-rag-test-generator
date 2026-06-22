package io.graphrag.builder.poc.fanout;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PoC gate V2-seeding: per-worker Connection 동시 seeding 무사고 — REQ-003.
 *
 * <h2>배경</h2>
 * <p>{@code BuilderCli.explore()}는 단일 {@code java.sql.Connection}을 모든
 * {@code EndpointExplorationRunner}에 전달한다(현재 순차라 안전). fan-out 병렬화 시
 * 이 공유 Connection에 여러 워커가 동시에 {@code insertSeeds}/{@code deleteSeeds}를
 * 호출하면 JDBC thread-safety 위반으로 레이스가 발생할 수 있다.
 *
 * <h2>방법론 — Testcontainers JDBC, petclinic 아님</h2>
 * <p>petclinic PoC 하니스는 SUT HTTP 탐색 경로를 검증하지 seeding 경로를 직접 테스트하지
 * 않는다. per-worker Connection 원칙(동일 DataSource에서 워커별 Connection 발급)을
 * 결정론적으로 검증하려면 DB 레벨 isolation이 필요하다. Testcontainers + PostgreSQL로
 * 독립 DB를 띄워 진짜 JDBC 동시 INSERT/DELETE를 측정한다. 이 방식은:
 * <ul>
 *   <li>Docker만 있으면 어디서든 재현 가능(외부 SUT 불필요)</li>
 *   <li>결정론적: 레이스가 있으면 SQLExceptions으로 포착됨</li>
 *   <li>핵심 원칙 증명에 집중: "동일 DataSource, 워커별 자기 Connection, 비중첩 키 범위,
 *       동시 INSERT/DELETE → 예외 0, 최종 행 수 일관"</li>
 * </ul>
 *
 * <h2>실행</h2>
 * <pre>
 * POC_FANOUT_E2E=1 ./gradlew :graph-rag-builder:test --tests '*V2ConcurrentSeedingPoc*'
 * </pre>
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "POC_FANOUT_E2E", matches = "1")
class V2ConcurrentSeedingPoc {

    private static final int WORKERS = 8;
    private static final int ROWS_PER_WORKER = 20;

    @Container
    @SuppressWarnings("resource")   // lifecycle managed by @Testcontainers
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    @DisplayName("REQ-003: 동시 seeding 무사고 + per-worker Connection")
    void perWorkerConnection_concurrentSeeding_noFailures() throws Exception {
        // ── 스키마 초기화 ──────────────────────────────────────────────────────
        try (Connection admin = openConnection();
             Statement st = admin.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS seed_probe (
                        id  INT PRIMARY KEY,
                        ep  TEXT NOT NULL
                    )
                    """);
        }
        System.out.printf("[V2-seeding] workers=%d rows_per_worker=%d%n", WORKERS, ROWS_PER_WORKER);

        // ── 동시 seeding 실행 ─────────────────────────────────────────────────
        AtomicInteger sqlExceptionCount = new AtomicInteger(0);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(WORKERS);
        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<Future<?>> futures = new ArrayList<>(WORKERS);

        for (int w = 0; w < WORKERS; w++) {
            int workerId = w;
            // 각 워커 전용 키 범위: [w*ROWS_PER_WORKER, (w+1)*ROWS_PER_WORKER - 1]
            int baseId = workerId * ROWS_PER_WORKER;

            futures.add(pool.submit(() -> {
                try {
                    startGate.await();  // 전 워커 동시 출발
                    // 워커 자기 Connection — 동일 JDBC URL, 각자 발급
                    try (Connection conn = openConnection()) {
                        conn.setAutoCommit(false);

                        // INSERT (insertSeeds 역할)
                        try (var ps = conn.prepareStatement(
                                "INSERT INTO seed_probe(id, ep) VALUES (?, ?)")) {
                            for (int i = 0; i < ROWS_PER_WORKER; i++) {
                                ps.setInt(1, baseId + i);
                                ps.setString(2, "ep-" + workerId);
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }
                        conn.commit();

                        // SELECT (querySingleRow 역할)
                        try (Statement st = conn.createStatement();
                             ResultSet rs = st.executeQuery(
                                     "SELECT COUNT(*) FROM seed_probe WHERE ep = 'ep-" + workerId + "'")) {
                            rs.next();
                            int count = rs.getInt(1);
                            System.out.printf("[V2-seeding] worker=%d SELECT count=%d%n",
                                    workerId, count);
                        }

                        // DELETE (deleteSeeds 역할)
                        try (var ps = conn.prepareStatement(
                                "DELETE FROM seed_probe WHERE id = ?")) {
                            for (int i = 0; i < ROWS_PER_WORKER; i++) {
                                ps.setInt(1, baseId + i);
                                ps.addBatch();
                            }
                            ps.executeBatch();
                        }
                        conn.commit();
                    }
                } catch (java.sql.SQLException ex) {
                    sqlExceptionCount.incrementAndGet();
                    System.err.printf("[V2-seeding] worker=%d SQLException: %s%n",
                            workerId, ex.getMessage());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }));
        }

        // 전 워커 동시 출발
        startGate.countDown();
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);

        pool.shutdownNow();
        for (Future<?> f : futures) {
            f.get();    // 미처리 예외 있으면 여기서 재투척됨
        }

        // ── 최종 상태 검증 ────────────────────────────────────────────────────
        int finalRowCount;
        try (Connection admin = openConnection();
             Statement st = admin.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM seed_probe")) {
            rs.next();
            finalRowCount = rs.getInt(1);
        }

        System.out.printf("[V2-seeding] finished=%b exceptions=%d finalRows=%d%n",
                finished, sqlExceptionCount.get(), finalRowCount);

        assertThat(finished)
                .as("모든 워커가 60초 내에 완료해야 한다")
                .isTrue();

        assertThat(sqlExceptionCount.get())
                .as("REQ-003: per-worker Connection 동시 seeding — SQLException 0건 (workers=%d)",
                        WORKERS)
                .isZero();

        assertThat(finalRowCount)
                .as("REQ-003: 모든 DELETE 후 seed_probe 행 수 0 (INSERT/DELETE 일관성)")
                .isZero();

        System.out.printf("[V2-seeding] PASS — workers=%d exceptions=0 finalRows=0%n", WORKERS);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Connection openConnection() throws java.sql.SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
