package io.graphrag.builder.run;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.env.SqlDialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;

/** probe seed row 삽입 (HTTP/WS 캡처 러너 공용). */
final class Seeds {

    private static final Logger log = LoggerFactory.getLogger(Seeds.class);

    private Seeds() {
    }

    /** probe row를 삽입한다. probe 값은 엔드포인트 스코프 키(P2-3, REQ-P007)이므로 endpoint 간 row 충돌 없음 → 멱등 INSERT. */
    static void insert(Connection connection, DbConfig.Type type,
                       SynthesizedInput.SeedRow seed) throws Exception {
        String sql = SqlDialect.idempotentInsert(type, seed.table(), seed.columns());
        if (log.isDebugEnabled()) {
            log.debug("seed insert sql: {}", sql);
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < seed.values().size(); i++) {
                statement.setObject(i + 1, seed.values().get(i));
            }
            statement.executeUpdate();
        }
        log.info("seeded: {} {}", seed.table(), seed.values());
    }

    /** 시드 행 삭제(PK=columns[0] 기준). 요청별 리셋용 — DELETE는 방언 불요. */
    static void delete(Connection connection, SynthesizedInput.SeedRow seed) {
        String sql = "DELETE FROM " + seed.table() + " WHERE " + seed.columns().get(0) + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, seed.values().get(0));
            statement.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("seed delete failed: " + seed.table(), e);
        }
    }
}
