package io.graphrag.testlib.internal;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 대시보드 이벤트용 INSERT/DELETE 테이블·키 추출. 실패 시 null (이벤트 생략). */
public final class SqlTableParser {

    public enum Kind { INSERT, DELETE }

    public record RowRef(Kind kind, String table, String keyColumn, Object keyValue) {
    }

    private static final Pattern INSERT = Pattern.compile(
            "^\\s*insert\\s+into\\s+([\\w.]+)\\s*\\(\\s*([\\w]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE = Pattern.compile(
            "^\\s*delete\\s+from\\s+([\\w.]+)\\s+where\\s+([\\w]+)\\s*=\\s*\\?", Pattern.CASE_INSENSITIVE);

    private SqlTableParser() {
    }

    public static RowRef parse(String sql, Object[] args) {
        Matcher insert = INSERT.matcher(sql);
        if (insert.find() && args.length > 0) {
            return new RowRef(Kind.INSERT, insert.group(1), insert.group(2), args[0]);
        }
        Matcher delete = DELETE.matcher(sql);
        if (delete.find() && args.length > 0) {
            return new RowRef(Kind.DELETE, delete.group(1), delete.group(2), args[0]);
        }
        return null;
    }
}
