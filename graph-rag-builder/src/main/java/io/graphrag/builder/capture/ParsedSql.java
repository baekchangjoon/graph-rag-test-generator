package io.graphrag.builder.capture;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 로그에서 파싱된 SQL 1건 + 바인딩. 컬럼 매핑은 SQL 텍스트에서 best-effort. */
public record ParsedSql(String sql, List<Binding> bindings) {

    public record Binding(int position, String value) {
    }

    private static final Pattern INSERT_TABLE = Pattern.compile(
            "^insert\\s+into\\s+([\\w.]+)\\s*\\(([^)]*)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_TABLE = Pattern.compile(
            "^update\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_TABLE = Pattern.compile(
            "^delete\\s+from\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SELECT_TABLE = Pattern.compile(
            "\\bfrom\\s+([\\w.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern COLUMN_EQ_PLACEHOLDER = Pattern.compile(
            "([\\w.]+)\\s*=\\s*\\?");

    public String kind() {
        return sql.trim().split("\\s+")[0].toUpperCase();
    }

    public String tableName() {
        Matcher m;
        switch (kind()) {
            case "INSERT" -> m = INSERT_TABLE.matcher(sql.trim());
            case "UPDATE" -> m = UPDATE_TABLE.matcher(sql.trim());
            case "DELETE" -> m = DELETE_TABLE.matcher(sql.trim());
            default -> m = SELECT_TABLE.matcher(sql);
        }
        return m.find() ? m.group(1) : "";
    }

    private static final Pattern TABLE_REF = Pattern.compile(
            "\\b(?:from|join)\\s+([\\w.]+)(?:\\s+(\\w+))?", Pattern.CASE_INSENSITIVE);
    private static final java.util.Set<String> NOT_ALIAS = java.util.Set.of(
            "on", "where", "left", "right", "inner", "outer", "cross", "join",
            "order", "group", "limit", "having", "union");

    /** position의 placeholder가 속한 테이블 (조인 별칭 해석). 모르면 주 테이블. */
    public String bindingTableForPosition(int position) {
        if (!kind().equals("SELECT")) {
            return tableName();
        }
        String rawColumn = rawColumnForPosition(position);
        int dot = rawColumn.lastIndexOf('.');
        if (dot < 0) {
            return tableName();
        }
        String alias = rawColumn.substring(0, dot);
        Matcher m = TABLE_REF.matcher(sql);
        while (m.find()) {
            String table = m.group(1);
            String tableAlias = m.group(2);
            if (tableAlias != null && !NOT_ALIAS.contains(tableAlias.toLowerCase())
                    && tableAlias.equals(alias)) {
                return table;
            }
            if (table.equals(alias)) {
                return table;
            }
        }
        return tableName();
    }

    private String rawColumnForPosition(int position) {
        Matcher m = COLUMN_EQ_PLACEHOLDER.matcher(sql);
        int index = 0;
        while (m.find()) {
            index++;
            if (index == position) {
                return m.group(1);
            }
        }
        return "";
    }

    /** position(1-base)의 placeholder가 대응하는 컬럼명. 모르면 "". */
    public String columnForPosition(int position) {
        if (kind().equals("INSERT")) {
            Matcher m = INSERT_TABLE.matcher(sql.trim());
            if (m.find()) {
                String[] columns = m.group(2).split(",");
                if (position >= 1 && position <= columns.length) {
                    return columns[position - 1].trim();
                }
            }
            return "";
        }
        // WHERE/SET의 col=? 시퀀스에서 position번째를 찾는다 (별칭 prefix 제거)
        Matcher m = COLUMN_EQ_PLACEHOLDER.matcher(sql);
        int index = 0;
        while (m.find()) {
            index++;
            if (index == position) {
                String column = m.group(1);
                int dot = column.lastIndexOf('.');
                return dot >= 0 ? column.substring(dot + 1) : column;
            }
        }
        return "";
    }
}
