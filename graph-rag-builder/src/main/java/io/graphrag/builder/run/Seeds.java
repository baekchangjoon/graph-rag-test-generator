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

    /** 여러 endpoint가 같은 probe row를 공유할 수 있다 → 멱등 INSERT. */
    static void insert(Connection connection, DbConfig.Type type,
                       SynthesizedInput.SeedRow seed) throws Exception {
        String sql = SqlDialect.idempotentInsert(type, seed.table(), seed.columns());
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < seed.values().size(); i++) {
                statement.setObject(i + 1, seed.values().get(i));
            }
            statement.executeUpdate();
        }
        log.info("seeded: {} {}", seed.table(), seed.values());
    }
}
