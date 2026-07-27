package io.graphrag.builder.provenance;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.TableSchema;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BooleanValue;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.feature.Feature;
import net.sf.jsqlparser.parser.feature.FeatureConfiguration;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.Values;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * seed.sql 화이트리스트 검증(REQ-010). JSqlParser(신규 의존성, {@code com.github.jsqlparser:jsqlparser})로
 * seed.sql의 각 문장을 구조적으로 파싱해 판정한다 — 정규식 기반 검사는 쓰지 않는다(기존 {@code testlib}의
 * regex 기반 {@code SqlTableParser}는 이 보안 게이트에 재사용하지 않는다, REQ-010 명시).
 *
 * <p><b>설계: blocklist가 아니라 allowlist(구조적 화이트리스트).</b> 세 차례의 코드리뷰에서 "새 절 발견 →
 * 개별 차단"이 반복됐다 — VALUES 절 표현식 종류(서브쿼리/함수/컬럼참조), upsert/RETURNING 절
 * (ON CONFLICT/ON DUPLICATE KEY UPDATE/RETURNING), 마지막으로 CTE({@code WITH ... INSERT})까지
 * 임의 서브쿼리 실행 경로였다(Postgres data-modifying CTE는 주 쿼리가 참조하지 않아도 실행이 보장된다).
 * blocklist는 구조적으로 "우리가 아직 생각하지 못한 절"에 항상 뚫린다. 그래서 이 클래스는
 * {@link net.sf.jsqlparser.statement.insert.Insert}의 **필드 전수**(JSqlParser 5.3 소스 기준, 아래
 * 열거)를 열거해 "허용 목록에 없는 필드가 하나라도 non-null/non-empty/true이면 reject"로 뒤집었다.
 *
 * <p><b>{@code Insert}의 전체 필드 18개(JSqlParser 5.3 {@code Insert.java} 소스 확인)와 판정:</b>
 * <ul>
 *   <li><b>허용(값 채움에 필요):</b> {@code table}(대상 테이블), {@code columns}(컬럼 목록),
 *       {@code select}(단, 런타임 타입이 정확히 {@link Values}여야 함 — VALUES 절).</li>
 *   <li><b>reject(아래 중 하나라도 존재/true면 절 자체를 reject — 내부를 들여다보지 않는다):</b>
 *       {@code oracleHint}, {@code partitions}, {@code onlyDefaultValues}, {@code overriding},
 *       {@code duplicateUpdateSets}(ON DUPLICATE KEY UPDATE), {@code modifierPriority}
 *       (LOW_PRIORITY/DELAYED/HIGH_PRIORITY), {@code modifierIgnore}(INSERT IGNORE),
 *       {@code overwrite}, {@code tableKeyword}, {@code returningClause}(RETURNING),
 *       {@code setUpdateSets}(INSERT ... SET), {@code withItemsList}(CTE — {@code WITH ... INSERT},
 *       읽기 전용이든 data-modifying이든 무조건 reject), {@code outputClause}(T-SQL OUTPUT),
 *       {@code conflictTarget}/{@code conflictAction}(ON CONFLICT).</li>
 * </ul>
 * ({@code isUseValues()}/{@code isUseSet()}/{@code isUseDuplicate()}/{@code isUseSelectBrackets()}는
 * 위 필드에서 파생되는 {@code @Deprecated} 편의 메서드라 별도 호출하지 않고 원본 필드의 getter만 쓴다 —
 * {@code isUseSelectBrackets()}는 소스 확인 결과 항상 {@code false}를 반환하는 죽은 API다.)
 *
 * <p><b>VALUES 절 리터럴 제한:</b> {@code select instanceof Values}로 확인한 뒤, 그 안의 각 표현식은
 * 닫힌 리터럴 집합({@link StringValue}/{@link LongValue}/{@link DoubleValue}/{@link NullValue}/
 * {@link BooleanValue}, {@link SignedExpression}로 감싼 부호 있는 상수 포함)만 허용한다 — 서브쿼리
 * ({@code (SELECT ...)}), 함수 호출(예: {@code LOAD_FILE(...)}), 컬럼 참조 등은 reject한다.
 *
 * <p><b>지원 문법:</b> {@code INSERT INTO t (cols) VALUES (닫힌 리터럴, ...)} 단일 문장뿐이며, 그 외
 * 모든 절은(이 Javadoc이 미처 나열하지 못한 것 포함 — allowlist이므로 자동으로) reject된다. 테이블 {@code t}는
 * **스키마 비한정 이름만** 허용한다 — {@code schema.table} 형태(예: {@code attacker.orders},
 * {@code public.orders})는 마지막 식별자({@code orders})가 화이트리스트에 있어도 reject한다. 대상 테이블명
 * 문자열만 비교하면 대상 DB에 동일명의 다른 스키마가 존재할 때 화이트리스트를 우회할 수 있는 조건부 갭이
 * 생기므로, 시드 대상은 provenance가 지목한 스키마 비한정 테이블로만 한정한다(seed.sql이 스키마를 명시할
 * 이유가 없다).
 *
 * <p>그 밖의 판정 규칙:
 * <ul>
 *   <li>{@code CCJSqlParserUtil} 파싱 결과가 {@link Insert}가 아니면 reject(DDL·UPDATE·DELETE 등).</li>
 *   <li>한 줄(=하나의 의도된 INSERT 문장, {@link TripleSynthesizer}가 seed.sql 한 줄에 문장 하나를 쓰는
 *       관례)이 실제로는 2개 이상 문장으로 파싱되면 reject — 세미콜론+주석 뒤 두 번째 문장, block-comment
 *       내부에 숨긴 문장 등 우회 시도를 잡아낸다. 문자열 리터럴 내부의 SQL 키워드(예:
 *       {@code VALUES ('DELETE FROM x')})는 파서가 리터럴로 인식하므로 문장 수에 영향을 주지 않아
 *       정상 통과한다.</li>
 *   <li>허용 테이블(화이트리스트) 밖을 대상으로 하는 INSERT는 reject.</li>
 * </ul>
 *
 * <p>파서는 {@link DbConfig.Type}에 따라 구성한다 — MySQL/MariaDB는 백슬래시 escape 문자열 리터럴을
 * 허용해야 하므로 {@link Feature#allowBackslashEscapeCharacter}를 켠다(표준 SQL/Postgres는 escape가
 * 작은따옴표 중복(''')이므로 기본값 유지). 백틱·이중따옴표 인용 식별자는 JSqlParser 5.3 문법이 방언
 * 구분 없이 기본 지원하므로 별도 설정이 필요 없다(실측 확인).
 */
public final class SeedSqlWhitelist {

    /** 화이트리스트 판정 결과. {@code accepted=false}면 {@code reasons}에 사유가 하나 이상 남는다. */
    public record WhitelistResult(boolean accepted, List<String> reasons) {
        static WhitelistResult accept() {
            return new WhitelistResult(true, List.of());
        }
    }

    /**
     * 리포트의 DB_READ 테이블 집합 + {@link TableSchema#foreignKeys()} 전이 폐포(재귀적으로 참조하는
     * 부모 테이블 전부)로 seed.sql 화이트리스트 테이블 집합을 계산한다(REQ-010). FK NOT NULL 제약을
     * 만족하려면 부모 행이 필요하므로 부모 테이블도 seed INSERT 대상으로 허용해야 한다 — 전이 폐포는
     * 스키마 사실만으로 결정적이다.
     */
    public static Set<String> transitiveWhitelist(Set<String> dbReadTables, List<TableSchema> tables) {
        Map<String, TableSchema> byName = new LinkedHashMap<>();
        for (TableSchema t : tables) {
            byName.put(t.name(), t);
        }
        Set<String> result = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>(dbReadTables);
        while (!stack.isEmpty()) {
            String table = stack.pop();
            if (!result.add(table)) {
                continue;   // 이미 방문(사이클 방어)
            }
            TableSchema schema = byName.get(table);
            if (schema == null) {
                continue;   // 스키마 미제공 — 더 이상 전이 확장 불가(해당 테이블 자체는 이미 화이트리스트됨)
            }
            for (ForeignKey fk : schema.foreignKeys()) {
                if (!result.contains(fk.referencedTable())) {
                    stack.push(fk.referencedTable());
                }
            }
        }
        return result;
    }

    /**
     * seed.sql 전체 내용을 검증한다. {@link TripleSynthesizer}는 seed.sql 한 줄에 INSERT 문장을 하나씩
     * 쓰므로, 빈 줄이 아닌 각 줄을 독립된 문장 텍스트로 취급해 개별 판정한다.
     */
    public WhitelistResult validate(String seedSqlContent, Set<String> whitelistedTables, DbConfig.Type dialect) {
        List<String> reasons = new ArrayList<>();
        for (String line : nonBlankLines(seedSqlContent)) {
            Optional<Insert> insert = parseSingleInsert(line, dialect, reasons);
            insert.ifPresent(ins -> {
                if (ins.getTable().getSchemaName() != null) {
                    reasons.add("seed.sql이 스키마 한정 테이블명을 사용함(REQ-010 reject) — 시드 대상은 "
                            + "provenance가 지목한 스키마 비한정 테이블뿐(대상 DB에 동일명 다른 스키마가 "
                            + "존재하면 화이트리스트를 우회할 수 있음): " + ins.getTable().getFullyQualifiedName());
                    return;
                }
                String table = ins.getTable().getUnquotedName();
                if (!whitelistedTables.contains(table)) {
                    reasons.add("seed.sql이 화이트리스트 밖 테이블을 대상으로 함(REQ-010 reject): " + table
                            + " (허용: " + whitelistedTables + ")");
                }
            });
        }
        return reasons.isEmpty() ? WhitelistResult.accept() : new WhitelistResult(false, reasons);
    }

    /**
     * 한 줄(하나의 의도된 문장 텍스트)을 파싱해 정확히 1개의 {@link Insert} 문장이고, 그 구조가 allowlist
     * ({@code INSERT INTO t (cols) VALUES (닫힌 리터럴, ...)} 단일 형태)에 정확히 부합하는지 확인한다
     * (REQ-010 정본 검사 — {@link TripleValidator}의 seed.sql 마커-diff(REQ-009) 컬럼→값 맵 추출도 이
     * 메서드를 재사용하므로, 여기서 거부된 표현식은 두 경로 모두에서 안전하게 배제된다). 파싱 실패, 2개
     * 이상 문장, INSERT가 아닌 문장, allowlist 밖 절 사용, 또는 VALUES 절에 상수가 아닌 표현식이 있으면
     * {@code reasons}에 사유를 남기고 {@link Optional#empty()}를 반환한다.
     */
    Optional<Insert> parseSingleInsert(String line, DbConfig.Type dialect, List<String> reasons) {
        Statements statements;
        try {
            statements = parseStatements(line, dialect);
        } catch (JSQLParserException e) {
            reasons.add("seed.sql 파싱 실패(REQ-010 reject): " + line + " — " + e.getMessage());
            return Optional.empty();
        }
        List<Statement> parsed = statements.getStatements();
        if (parsed.size() != 1) {
            reasons.add("seed.sql 한 문장에 다중 SQL 문장 감지(REQ-010 reject — 우회 시도 의심): " + line);
            return Optional.empty();
        }
        Statement statement = parsed.get(0);
        if (!(statement instanceof Insert)) {
            reasons.add("seed.sql 문장이 INSERT가 아님(REQ-010 reject): " + line);
            return Optional.empty();
        }
        Insert insert = (Insert) statement;
        List<String> violations = allowlistViolations(insert);
        if (!violations.isEmpty()) {
            reasons.add("seed.sql이 allowlist 밖 구성요소를 사용함(REQ-010 reject) — 허용 문법은 단일 "
                    + "INSERT INTO t (cols) VALUES(닫힌 리터럴)뿐: " + violations + " (line: " + line + ")");
            return Optional.empty();
        }
        // violations가 비었다는 것은 select가 정확히 Values임을 의미(allowlistViolations가 보장) — 안전.
        for (Expression value : insert.getValues().getExpressions()) {
            if (!isClosedLiteral(value)) {
                reasons.add("seed.sql VALUES 절에 상수 리터럴이 아닌 표현식 감지(REQ-010 reject — 임의 SQL "
                        + "표현식 삽입 의심): " + value.getClass().getSimpleName() + " = " + value
                        + " (line: " + line + ")");
                return Optional.empty();
            }
        }
        return Optional.of(insert);
    }

    /**
     * {@link Insert}의 필드 18개(class Javadoc 열거) 중 허용 목록(table/columns/select-as-Values)에
     * 없는 필드가 non-null·non-empty·true이면 그 이름을 사유 목록에 담아 반환한다. 반환 목록이 비어
     * 있어야만 이 INSERT를 allowlist 통과로 간주한다 — 새 JSqlParser 버전이 필드를 추가해도, 그 필드가
     * 이 열거에 없으면 (allowlist 설계상) 자동으로는 통과시키지 않고 컴파일 시점에는 드러나지 않지만
     * 런타임 동작은 "알 수 없는 필드는 검사하지 않는" 사각지대가 될 수 있으므로, JSqlParser를 업그레이드할
     * 때는 이 열거를 {@code Insert.java} 소스와 다시 대조해야 한다(Javadoc에 소스 대조 방법 명시).
     */
    private static List<String> allowlistViolations(Insert insert) {
        List<String> violations = new ArrayList<>();
        if (!(insert.getSelect() instanceof Values)) {
            violations.add("select(VALUES 절이 아니거나 없음 — INSERT ... SELECT/서브쿼리/DEFAULT VALUES 등)");
        }
        // C4 리뷰 Critical 3(c): 컬럼 목록이 없는 INSERT INTO t VALUES (...)는 어떤 값이 어떤 컬럼에
        // 들어가는지 SQL 텍스트만으로 결정할 수 없다 — TrialRunner의 역-DELETE 추적(정리 키 해석)이
        // 성립하지 않아 "삽입됐지만 정리 대상에서 누락되는" 행을 만든다. allowlist에서 reject한다.
        if (insert.getColumns() == null || insert.getColumns().isEmpty()) {
            violations.add("columns(컬럼 목록 없는 INSERT INTO t VALUES (...) — 정리 키 추적 불가)");
        }
        if (insert.getOracleHint() != null) {
            violations.add("oracleHint");
        }
        if (nonEmpty(insert.getPartitions())) {
            violations.add("partitions");
        }
        if (insert.isOnlyDefaultValues()) {
            violations.add("onlyDefaultValues");
        }
        if (insert.isOverriding()) {
            violations.add("overriding");
        }
        if (nonEmpty(insert.getDuplicateUpdateSets())) {
            violations.add("duplicateUpdateSets(ON DUPLICATE KEY UPDATE)");
        }
        if (insert.getModifierPriority() != null) {
            violations.add("modifierPriority(LOW_PRIORITY/DELAYED/HIGH_PRIORITY)");
        }
        if (insert.isModifierIgnore()) {
            violations.add("modifierIgnore(INSERT IGNORE)");
        }
        if (insert.isOverwrite()) {
            violations.add("overwrite");
        }
        if (insert.isTableKeyword()) {
            violations.add("tableKeyword");
        }
        if (insert.getReturningClause() != null) {
            violations.add("returningClause(RETURNING)");
        }
        if (nonEmpty(insert.getSetUpdateSets())) {
            violations.add("setUpdateSets(INSERT ... SET)");
        }
        if (nonEmpty(insert.getWithItemsList())) {
            violations.add("withItemsList(CTE — WITH ... INSERT)");
        }
        if (insert.getOutputClause() != null) {
            violations.add("outputClause(T-SQL OUTPUT)");
        }
        if (insert.getConflictTarget() != null) {
            violations.add("conflictTarget(ON CONFLICT)");
        }
        if (insert.getConflictAction() != null) {
            violations.add("conflictAction(ON CONFLICT ... DO ...)");
        }
        return violations;
    }

    private static boolean nonEmpty(List<?> list) {
        return list != null && !list.isEmpty();
    }

    /**
     * {@code VALUES} 절 표현식이 닫힌 리터럴 집합에 속하는지 판정한다. 서브쿼리
     * ({@link net.sf.jsqlparser.statement.select.ParenthesedSelect}), 함수 호출({@link
     * net.sf.jsqlparser.expression.Function}), 컬럼 참조({@link net.sf.jsqlparser.schema.Column}) 등은
     * 전부 거부된다 — 마커 위치는 값 하나를 치환하는 자리이지, 임의 SQL 표현식으로 대체할 자리가 아니다.
     * {@link SignedExpression}(단항 부호, 예: {@code -5})은 <b>감싼 내부가 수치 리터럴일 때만</b>
     * 재귀적으로 허용한다 — {@code -'x'}/{@code +NULL}처럼 부호를 붙일 수 없는 리터럴 조합은 여기서
     * 거부한다(Phase A 후속 Important 1). 이 판정 집합은 {@link TrialRunner}의 {@code closedLiteralValue}
     * (같은 개념을 "바인딩 가능한 Java 값"으로 환산하는 2차 방어선)와 정확히 일치해야 한다: 게이트가
     * 통과시킨 표현식은 TrialRunner가 반드시 값으로 환산할 수 있어야 하고, 그 반대도 성립해야 한다.
     * 유일한 의도적 예외는 최상위 {@link NullValue}로, 여기서는 통과하지만 TrialRunner에서는 값이 아니라
     * {@code setNull} 바인딩으로 별도 처리된다(PK 정리 키로는 쓰이지 않는다).
     */
    private static boolean isClosedLiteral(Expression expr) {
        if (expr instanceof SignedExpression) {
            return isSignedNumericLiteral(((SignedExpression) expr).getExpression());
        }
        return expr instanceof StringValue
                || expr instanceof LongValue
                || expr instanceof DoubleValue
                || expr instanceof NullValue
                || expr instanceof BooleanValue;
    }

    /**
     * 단항 부호가 감쌀 수 있는 유일한 대상 — 수치 리터럴({@link LongValue}/{@link DoubleValue})인지
     * 판정한다. 중첩 부호({@code --5})도 결국 수치 리터럴에 도달해야만 허용한다.
     */
    private static boolean isSignedNumericLiteral(Expression expr) {
        if (expr instanceof SignedExpression) {
            return isSignedNumericLiteral(((SignedExpression) expr).getExpression());
        }
        return expr instanceof LongValue || expr instanceof DoubleValue;
    }

    private static Statements parseStatements(String sql, DbConfig.Type dialect) throws JSQLParserException {
        FeatureConfiguration configuration = new FeatureConfiguration();
        if (dialect == DbConfig.Type.MYSQL || dialect == DbConfig.Type.MARIADB) {
            configuration.setValue(Feature.allowBackslashEscapeCharacter, true);
        }
        return CCJSqlParserUtil.parseStatements(sql, parser -> parser.withConfiguration(configuration));
    }

    static List<String> nonBlankLines(String content) {
        List<String> lines = new ArrayList<>();
        for (String raw : content.split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }
}
