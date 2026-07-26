package io.graphrag.builder.provenance;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.builder.provenance.SeedSqlWhitelist.WhitelistResult;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeedSqlWhitelist} 검증(REQ-010) — JSqlParser 기반 seed.sql 화이트리스트.
 * 정규식 기반 검사는 쓰지 않는다(기존 {@code testlib}의 {@code SqlTableParser}는 이 보안 게이트에
 * 재사용하지 않음 — REQ-010 명시).
 */
class SeedSqlWhitelistIT {

    private final SeedSqlWhitelist whitelist = new SeedSqlWhitelist();

    @Test
    @DisplayName("REQ-010: 우회 시도 1 — 세미콜론+주석 뒤 두 번째 문장(DELETE)은 화이트리스트 밖 문장으로 reject")
    void req010_semicolonCommentSecondStatementRejected() {
        String seedSql = "INSERT INTO orders (id) VALUES ('a'); -- x\nDELETE FROM orders;";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted()).as("세미콜론+주석으로 숨긴 두 번째 문장은 reject되어야 한다").isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-010: 우회 시도 2 — block-comment 내부에 숨긴 다중 문장(DELETE)은 reject")
    void req010_blockCommentMultiStatementRejected() {
        String seedSql = "INSERT INTO orders (id) VALUES ('a') /* c */; DELETE FROM orders;";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted()).as("block-comment로 은닉한 다중 문장은 reject되어야 한다").isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-010: 우회 시도 3 — 문자열 리터럴 속 DELETE 키워드는 데이터일 뿐이므로 통과한다")
    void req010_deleteKeywordInsideStringLiteralPasses() {
        String seedSql = "INSERT INTO orders (id, note) VALUES ('a', 'DELETE FROM x');";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted())
                .as("문자열 리터럴 안의 'DELETE FROM x'는 SQL 문장이 아니라 데이터이므로 통과해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("REQ-010: 화이트리스트 밖 테이블을 대상으로 한 INSERT는 reject된다")
    void req010_nonWhitelistedTableRejected() {
        String seedSql = "INSERT INTO secret_admin_table (id) VALUES ('a');";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted()).as("화이트리스트(DB_READ+FK 전이 폐포) 밖 테이블은 reject되어야 한다").isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("secret_admin_table"));
    }

    @Test
    @DisplayName("REQ-010: FK 전이 폐포 — DB_READ 집합 밖이지만 FK로 참조되는 부모 테이블(accounts) INSERT는 통과한다")
    void req010_fkTransitiveParentTableIsWhitelisted() {
        TableSchema transfers = new TableSchema(
                "transfers",
                List.of(new ColumnSchema("id", "VARCHAR", false, true),
                        new ColumnSchema("account_id", "VARCHAR", false, false)),
                List.of(new ForeignKey("account_id", "accounts", "id")),
                List.of());
        TableSchema accounts = new TableSchema(
                "accounts",
                List.of(new ColumnSchema("id", "VARCHAR", false, true)),
                List.of(),
                List.of());

        Set<String> transitive = SeedSqlWhitelist.transitiveWhitelist(Set.of("transfers"), List.of(transfers, accounts));
        assertThat(transitive)
                .as("DB_READ 테이블(transfers) + FK 전이 참조 부모(accounts)가 모두 화이트리스트에 있어야 한다")
                .containsExactlyInAnyOrder("transfers", "accounts");

        String seedSql = "INSERT INTO accounts (id) VALUES ('acc-1');\n"
                + "INSERT INTO transfers (id, account_id) VALUES ('t-1', 'acc-1');";

        WhitelistResult result = whitelist.validate(seedSql, transitive, DbConfig.Type.POSTGRES);
        assertThat(result.accepted())
                .as("FK 전이 참조 부모 테이블(accounts)과 DB_READ 테이블(transfers) 모두 통과해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("REQ-010: 방언별 대표 INSERT 3건 — Postgres(double-quote)/MySQL(backtick+backslash escape)/"
            + "MariaDB(backtick)가 각각 올바르게 판정된다")
    void req010_dialectSpecificInsertsJudgedCorrectly() {
        WhitelistResult postgres = whitelist.validate(
                "INSERT INTO \"orders\" (\"id\") VALUES ('a');", Set.of("orders"), DbConfig.Type.POSTGRES);
        assertThat(postgres.accepted()).as("Postgres 이중따옴표 인용 식별자는 통과해야 한다").isTrue();

        WhitelistResult mysql = whitelist.validate(
                "INSERT INTO `orders` (`id`, `note`) VALUES ('a', 'it\\'s fine');",
                Set.of("orders"), DbConfig.Type.MYSQL);
        assertThat(mysql.accepted())
                .as("MySQL 백틱 인용 식별자 + 백슬래시 escape 문자열 리터럴은 통과해야 한다")
                .isTrue();

        WhitelistResult mariadb = whitelist.validate(
                "INSERT INTO `orders` (`id`) VALUES ('a');", Set.of("orders"), DbConfig.Type.MARIADB);
        assertThat(mariadb.accepted()).as("MariaDB 백틱 인용 식별자는 통과해야 한다").isTrue();
    }

    @Test
    @DisplayName("REQ-010/jsql-defer 해소: 갭 마커(REQ-007) 리터럴이 포함된 seed.sql이 실제 JSqlParser로 "
            + "예외 없이 단일 INSERT로 파싱된다 — Task 9 각주의 구조 검증 대체물을 실제 파서 검증으로 갈음")
    void req010_gapMarkerLiteralParsesAsInsertWithoutException() {
        String seedSql = "INSERT INTO orders (id, risk_score) VALUES "
                + "('a', '" + TripleSynthesizer.GAP_MARKER_PREFIX + "{type:long, semanticHint:none, guard:none}');";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted())
                .as("갭 마커를 포함한 seed.sql도 예외 없이 단일 INSERT로 파싱되어 화이트리스트를 통과해야 한다")
                .isTrue();
    }

    @Test
    @DisplayName("REQ-010: DDL/UPDATE/DELETE 단문은 INSERT가 아니므로 reject된다")
    void req010_nonInsertStatementsRejected() {
        assertThat(whitelist.validate("DELETE FROM orders;", Set.of("orders"), DbConfig.Type.POSTGRES).accepted())
                .as("DELETE 단문은 reject되어야 한다").isFalse();
        assertThat(whitelist.validate("UPDATE orders SET id='x';", Set.of("orders"), DbConfig.Type.POSTGRES).accepted())
                .as("UPDATE 단문은 reject되어야 한다").isFalse();
        assertThat(whitelist.validate("DROP TABLE orders;", Set.of("orders"), DbConfig.Type.POSTGRES).accepted())
                .as("DDL(DROP TABLE) 단문은 reject되어야 한다").isFalse();
    }

    // ---- 리뷰 Critical 1: VALUES 절 표현식 종류 제한 (닫힌 리터럴 집합만 허용) ----

    @Test
    @DisplayName("REQ-010(fix): VALUES 절의 서브쿼리((SELECT ...))는 임의 데이터 유출 경로이므로 reject된다")
    void req010_subqueryInValuesRejected() {
        String seedSql = "INSERT INTO orders (id, secret) VALUES "
                + "('x', (SELECT password FROM admin_users LIMIT 1));";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted())
                .as("마커 위치라도 서브쿼리로의 대체는 '값 치환'이 아니므로 reject되어야 한다")
                .isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-010(fix): VALUES 절의 함수 호출(예: LOAD_FILE(...))은 reject된다")
    void req010_functionCallInValuesRejected() {
        String seedSql = "INSERT INTO orders (id, secret) VALUES ('x', LOAD_FILE('/etc/passwd'));";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted()).as("함수 호출로의 대체는 reject되어야 한다").isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-010(fix): VALUES 절의 컬럼 참조(다른 컬럼 값을 그대로 노출)는 reject된다")
    void req010_columnReferenceInValuesRejected() {
        String seedSql = "INSERT INTO orders (id, note) VALUES ('a', other_col);";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted()).as("컬럼 참조로의 대체는 reject되어야 한다").isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-010(fix): 정상 리터럴(음수·NULL·불리언 포함)은 통과한다")
    void req010_ordinaryLiteralsIncludingNegativeAndNullAccepted() {
        String seedSql = "INSERT INTO orders (id, amt, note, flag) VALUES ('a', -5, NULL, true);";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted())
                .as("문자열/음수/NULL/불리언 리터럴만 있는 정상 INSERT는 통과해야 한다: " + result.reasons())
                .isTrue();
    }

    // ---- 리뷰 Important 3: VALUES 절이 없는 INSERT(INSERT ... SELECT) reject ----

    @Test
    @DisplayName("REQ-010(fix): VALUES 절 없이 SELECT로 값을 채우는 INSERT ... SELECT는 reject된다")
    void req010_insertSelectWithoutValuesClauseRejected() {
        String seedSql = "INSERT INTO orders (id) SELECT id FROM staging;";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted()).as("INSERT ... SELECT(VALUES 없음)는 reject되어야 한다").isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    // ---- 재리뷰 Critical 잔여: upsert/RETURNING 절 내부 표현식은 VALUES 검사망 밖 — 절 자체를 reject ----

    @Test
    @DisplayName("REQ-010(fix2): Postgres ON CONFLICT ... DO UPDATE 절 내부 서브쿼리는 reject된다 "
            + "(VALUES 리터럴 검사만으로는 무방비 — isClosedLiteral이 getValues()만 보고 conflictAction은 안 봄)")
    void req010_postgresOnConflictDoUpdateSubqueryRejected() {
        String seedSql = "INSERT INTO orders (id) VALUES ('a') ON CONFLICT (id) DO UPDATE SET secret = "
                + "(SELECT password FROM admin_users LIMIT 1);";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted())
                .as("ON CONFLICT ... DO UPDATE 절은 지원하지 않으므로(seed 목적상 불필요) reject되어야 한다")
                .isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-010(fix2): MySQL ON DUPLICATE KEY UPDATE 절 내부 서브쿼리는 reject된다")
    void req010_mysqlOnDuplicateKeyUpdateSubqueryRejected() {
        String seedSql = "INSERT INTO orders (id, secret) VALUES ('a','x') ON DUPLICATE KEY UPDATE secret = "
                + "(SELECT password FROM admin_users LIMIT 1);";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.MYSQL);

        assertThat(result.accepted())
                .as("ON DUPLICATE KEY UPDATE 절은 지원하지 않으므로 reject되어야 한다")
                .isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-010(fix2): RETURNING 절 내부 서브쿼리는 reject된다")
    void req010_returningClauseSubqueryRejected() {
        String seedSql = "INSERT INTO orders (id) VALUES ('a') RETURNING (SELECT password FROM admin_users LIMIT 1);";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted()).as("RETURNING 절은 지원하지 않으므로 reject되어야 한다").isFalse();
        assertThat(result.reasons()).isNotEmpty();
    }

    @Test
    @DisplayName("REQ-010(fix2): upsert/RETURNING 절이 전혀 없는 정상 INSERT는 여전히 통과한다(무회귀 확인)")
    void req010_plainInsertWithoutUpsertOrReturningStillAccepted() {
        String seedSql = "INSERT INTO orders (id, note) VALUES ('a', 'hello');";

        WhitelistResult result = whitelist.validate(seedSql, Set.of("orders"), DbConfig.Type.POSTGRES);

        assertThat(result.accepted())
                .as("upsert/RETURNING 절이 없는 정상 INSERT는 통과해야 한다(회귀 없음): " + result.reasons())
                .isTrue();
    }
}
