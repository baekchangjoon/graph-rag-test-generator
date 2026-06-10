package io.graphrag.builder.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SUT stdout 로그에서 SQL + 바인딩을 한 번의 스캔으로 추출한다 (발행 순서 보존).
 *
 * 지원 형식 (모두 env 주입만으로 활성화, SUT 무수정):
 * - Hibernate: logging.level.org.hibernate.SQL=DEBUG + org.hibernate.orm.jdbc.bind=TRACE
 * - MyBatis:   logging.level.<mapper-namespace>=TRACE ("==>  Preparing:" / "==> Parameters:")
 */
public final class SqlLogParser {

    private static final Pattern HIBERNATE_SQL = Pattern.compile(
            "org\\.hibernate\\.SQL\\s*:\\s*(.+)$");
    private static final Pattern HIBERNATE_BIND = Pattern.compile(
            "org\\.hibernate\\.orm\\.jdbc\\.bind\\s*:\\s*binding parameter \\((\\d+):[A-Za-z_]+\\) <- \\[(.*)\\]\\s*$");
    private static final Pattern MYBATIS_SQL = Pattern.compile(
            "==>\\s+Preparing:\\s*(.+)$");
    private static final Pattern MYBATIS_PARAMS = Pattern.compile(
            "==>\\s*Parameters:\\s*(.*)$");
    /** "값(Type)" 토큰. 값에 ", "가 포함되면 best-effort. */
    private static final Pattern MYBATIS_PARAM_TOKEN = Pattern.compile(
            "(.+?)\\(([A-Za-z][A-Za-z0-9.]*)\\)$");

    private SqlLogParser() {
    }

    public static List<ParsedSql> parse(String log) {
        List<ParsedSql> result = new ArrayList<>();
        String currentSql = null;
        List<ParsedSql.Binding> currentBindings = new ArrayList<>();

        for (String line : log.split("\\R")) {
            Matcher hibernateSql = HIBERNATE_SQL.matcher(line);
            if (hibernateSql.find()) {
                flush(result, currentSql, currentBindings);
                currentSql = hibernateSql.group(1).trim();
                currentBindings = new ArrayList<>();
                continue;
            }
            Matcher mybatisSql = MYBATIS_SQL.matcher(line);
            if (mybatisSql.find()) {
                flush(result, currentSql, currentBindings);
                currentSql = mybatisSql.group(1).trim();
                currentBindings = new ArrayList<>();
                continue;
            }

            Matcher hibernateBind = HIBERNATE_BIND.matcher(line);
            if (hibernateBind.find() && currentSql != null) {
                currentBindings.add(new ParsedSql.Binding(
                        Integer.parseInt(hibernateBind.group(1)), hibernateBind.group(2)));
                continue;
            }

            Matcher mybatisParams = MYBATIS_PARAMS.matcher(line);
            if (mybatisParams.find() && currentSql != null) {
                currentBindings.addAll(parseMybatisParams(mybatisParams.group(1)));
            }
        }
        flush(result, currentSql, currentBindings);
        return result;
    }

    private static List<ParsedSql.Binding> parseMybatisParams(String params) {
        List<ParsedSql.Binding> bindings = new ArrayList<>();
        if (params.isBlank()) {
            return bindings;
        }
        int position = 0;
        for (String token : params.split(", ")) {
            position++;
            Matcher matcher = MYBATIS_PARAM_TOKEN.matcher(token.trim());
            bindings.add(new ParsedSql.Binding(position,
                    matcher.matches() ? matcher.group(1) : token.trim()));
        }
        return bindings;
    }

    private static void flush(List<ParsedSql> result, String sql, List<ParsedSql.Binding> bindings) {
        if (sql != null) {
            result.add(new ParsedSql(sql, List.copyOf(bindings)));
        }
    }
}
