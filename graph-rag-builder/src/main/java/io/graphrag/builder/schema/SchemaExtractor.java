package io.graphrag.builder.schema;

import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.TableSchema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** JDBC DatabaseMetaData → 물리 스키마 사실 (운영 동일 DBMS 기준, docs/03 L2). */
public class SchemaExtractor {

    public List<TableSchema> extract(Connection connection) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        // MySQL/MariaDB는 catalog=database, schema=null; Postgres 등은 catalog=null, schema="public".
        String product = meta.getDatabaseProductName().toLowerCase(Locale.ROOT);
        boolean mysqlFamily = product.contains("mysql") || product.contains("mariadb");
        String catalog = mysqlFamily ? connection.getCatalog() : null;
        String schema = mysqlFamily ? null : "public";

        List<TableSchema> tables = new ArrayList<>();
        try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[]{"TABLE"})) {
            while (rs.next()) {
                tables.add(extractTable(meta, catalog, schema, rs.getString("TABLE_NAME")));
            }
        }
        tables.sort((a, b) -> a.name().compareTo(b.name()));
        return tables;
    }

    private TableSchema extractTable(DatabaseMetaData meta, String catalog, String schema, String table)
            throws SQLException {
        Set<String> primaryKeys = new LinkedHashSet<>();
        try (ResultSet rs = meta.getPrimaryKeys(catalog, schema, table)) {
            while (rs.next()) {
                primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
        }

        List<ColumnSchema> columns = new ArrayList<>();
        try (ResultSet rs = meta.getColumns(catalog, schema, table, "%")) {
            while (rs.next()) {
                String name = rs.getString("COLUMN_NAME");
                columns.add(new ColumnSchema(
                        name,
                        rs.getString("TYPE_NAME").toUpperCase(),
                        "YES".equals(rs.getString("IS_NULLABLE")),
                        primaryKeys.contains(name),
                        "YES".equals(rs.getString("IS_AUTOINCREMENT"))));
            }
        }

        List<ForeignKey> foreignKeys = new ArrayList<>();
        try (ResultSet rs = meta.getImportedKeys(catalog, schema, table)) {
            while (rs.next()) {
                foreignKeys.add(new ForeignKey(
                        rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKTABLE_NAME"),
                        rs.getString("PKCOLUMN_NAME")));
            }
        }

        Map<String, List<String>> uniqueIndexes = new LinkedHashMap<>();
        try (ResultSet rs = meta.getIndexInfo(catalog, schema, table, true, false)) {
            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (indexName != null && column != null) {
                    uniqueIndexes.computeIfAbsent(indexName, k -> new ArrayList<>()).add(column);
                }
            }
        }

        return new TableSchema(table, columns, foreignKeys,
                new ArrayList<>(uniqueIndexes.values()));
    }
}
