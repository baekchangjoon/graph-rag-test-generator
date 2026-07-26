package io.graphrag.builder.provenance;

import io.graphrag.builder.env.DbConfig;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.TableSchema;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.feature.Feature;
import net.sf.jsqlparser.parser.feature.FeatureConfiguration;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.insert.Insert;

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
 * <p>판정 규칙:
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
     * 한 줄(하나의 의도된 문장 텍스트)을 파싱해 정확히 1개의 {@link Insert} 문장인지 확인한다.
     * 파싱 실패, 2개 이상 문장, 또는 INSERT가 아닌 문장이면 {@code reasons}에 사유를 남기고
     * {@link Optional#empty()}를 반환한다. {@link TripleValidator}가 seed.sql 마커-diff(REQ-009)의
     * 컬럼→값 맵 추출에도 이 메서드를 재사용한다(같은 패키지, 파싱 로직 단일화).
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
        return Optional.of((Insert) statement);
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
