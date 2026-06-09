package io.graphrag.builder.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SUT stdout의 Hibernate 로그에서 SQL + 바인딩을 추출한다.
 * 전제 (env로 활성화, SUT 무수정):
 *   logging.level.org.hibernate.SQL=DEBUG
 *   logging.level.org.hibernate.orm.jdbc.bind=TRACE
 */
public final class HibernateSqlLogParser {

    private static final Pattern SQL_LINE = Pattern.compile(
            "org\\.hibernate\\.SQL\\s*:\\s*(.+)$");
    private static final Pattern BIND_LINE = Pattern.compile(
            "org\\.hibernate\\.orm\\.jdbc\\.bind\\s*:\\s*binding parameter \\((\\d+):[A-Za-z_]+\\) <- \\[(.*)\\]\\s*$");

    private HibernateSqlLogParser() {
    }

    public static List<ParsedSql> parse(String log) {
        List<ParsedSql> result = new ArrayList<>();
        String currentSql = null;
        List<ParsedSql.Binding> currentBindings = new ArrayList<>();

        for (String line : log.split("\\R")) {
            Matcher sql = SQL_LINE.matcher(line);
            if (sql.find()) {
                flush(result, currentSql, currentBindings);
                currentSql = sql.group(1).trim();
                currentBindings = new ArrayList<>();
                continue;
            }
            Matcher bind = BIND_LINE.matcher(line);
            if (bind.find() && currentSql != null) {
                currentBindings.add(new ParsedSql.Binding(
                        Integer.parseInt(bind.group(1)), bind.group(2)));
            }
        }
        flush(result, currentSql, currentBindings);
        return result;
    }

    private static void flush(List<ParsedSql> result, String sql, List<ParsedSql.Binding> bindings) {
        if (sql != null) {
            result.add(new ParsedSql(sql, List.copyOf(bindings)));
        }
    }
}
