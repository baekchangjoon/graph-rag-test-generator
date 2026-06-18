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
    /** Hibernate 5 BasicBinder. 로거명은 logback %logger{36}로 축약될 수 있어 "BasicBinder"만 키로 잡는다. */
    private static final Pattern HIBERNATE_BIND_H5 = Pattern.compile(
            "BasicBinder\\b.*?binding parameter \\[(\\d+)\\] as \\[[^\\]]*\\] - \\[(.*)\\]\\s*$");
    private static final Pattern MYBATIS_SQL = Pattern.compile(
            "==>\\s+Preparing:\\s*(.+)$");
    private static final Pattern MYBATIS_PARAMS = Pattern.compile(
            "==>\\s*Parameters:\\s*(.*)$");
    /** "값(Type)" 토큰. 값에 ", "가 포함되면 best-effort. */
    private static final Pattern MYBATIS_PARAM_TOKEN = Pattern.compile(
            "(.+?)\\(([A-Za-z][A-Za-z0-9.]*)\\)$");
    /**
     * 로그 prefix(첫 " : " 이전)에서 trace 토큰 후보를 찾는다.
     * 좌측 경계(?<![A-Za-z0-9-])로 myTraceId= 같은 더 긴 키의 접미 오탐을 막고,
     * 우측 경계(?![0-9a-fA-F])로 32 초과 토큰의 묵음 절단을 막는다(둘 다 거부).
     */
    private static final Pattern MDC_TRACE = Pattern.compile(
            "(?i)(?<![A-Za-z0-9-])(?:traceId|X-B3-TraceId)=([0-9a-fA-F]{16,32})(?![0-9a-fA-F])");
    /**
     * Sleuth MDC 브래킷. 3-field [app,traceId,spanId] (Sleuth 3.x) 와
     * 4-field [app,traceId,spanId,exportable] (Sleuth 1.x/2.x, Java8 레거시 기본) 둘 다 매칭.
     */
    private static final Pattern SLEUTH_BRACKET = Pattern.compile(
            "\\[[^,\\]]*,([0-9a-fA-F]{16,32}),[0-9a-fA-F]{1,32}(?:,[^\\]]*)?\\]");

    private SqlLogParser() {
    }

    /**
     * 라인의 로그 prefix(로거명 ":" 구분자 이전)에서만 trace 토큰을 추출한다(소문자 hex).
     * SQL 본문/bind 값의 hex를 trace로 오탐하지 않도록 " : " 이후는 보지 않는다.
     */
    public static String extractTraceId(String line) {
        int sep = line.indexOf(" : ");
        String prefix = sep < 0 ? line : line.substring(0, sep);
        Matcher mdc = MDC_TRACE.matcher(prefix);
        if (mdc.find()) {
            return mdc.group(1).toLowerCase(java.util.Locale.ROOT);
        }
        Matcher bracket = SLEUTH_BRACKET.matcher(prefix);
        if (bracket.find()) {
            return bracket.group(1).toLowerCase(java.util.Locale.ROOT);
        }
        return null;
    }

    /** expected(32 hex)와 lineToken이 같거나, lineToken이 expected의 우측 16 hex와 같으면 true(대소문자 무관). */
    public static boolean traceIdMatches(String expected, String lineToken) {
        if (expected == null || lineToken == null) {
            return false;
        }
        String e = expected.toLowerCase(java.util.Locale.ROOT);
        String t = lineToken.toLowerCase(java.util.Locale.ROOT);
        if (e.equals(t)) {
            return true;
        }
        return e.length() == 32 && t.equals(e.substring(16));
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

            Matcher hibernateBindH5 = HIBERNATE_BIND_H5.matcher(line);
            if (hibernateBindH5.find() && currentSql != null) {
                currentBindings.add(new ParsedSql.Binding(
                        Integer.parseInt(hibernateBindH5.group(1)), hibernateBindH5.group(2)));
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
