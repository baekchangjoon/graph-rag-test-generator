package io.graphrag.builder.run;

import io.graphrag.builder.index.FormFieldBinding;
import io.graphrag.model.ColumnSchema;
import io.graphrag.model.ForeignKey;
import io.graphrag.model.TableSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 폼 참조 백업 테이블 해석 우선순위(spec §3.2): FK joinColumn → @Table → camelToSnake. */
class FormReferenceBackupTableTest {

    private static final TableSchema TYPES = new TableSchema("types",
            List.of(new ColumnSchema("id", "BIGINT", false, true)), List.of(), List.of());
    private static final TableSchema COLORS = new TableSchema("colors",
            List.of(new ColumnSchema("id", "BIGINT", false, true),
                    new ColumnSchema("name", "VARCHAR", false, false)), List.of(), List.of());
    private static final TableSchema BRAND = new TableSchema("brand",
            List.of(new ColumnSchema("id", "BIGINT", false, true)), List.of(), List.of());

    @Test
    void resolvesByJoinColumnForeignKeyFirst() {
        // 커맨드 테이블이 type_id FK[types]를 가질 때 @ManyToOne @JoinColumn(type_id) → "types".
        TableSchema pets = new TableSchema("pets",
                List.of(new ColumnSchema("type_id", "BIGINT", false, false)),
                List.of(new ForeignKey("type_id", "types", "id")), List.of());
        FormFieldBinding binding = FormFieldBinding.reference("type", "x.PetType", "x.PetType", "type_id", "ignored");

        assertThat(EndpointExplorationRunner.resolveBackupTable(binding, List.of(pets, TYPES)))
                .isEqualTo("types");
    }

    @Test
    void fallsBackToStaticTableAnnotationWhenNoJoinColumn() {
        // joinColumn 없음 + @Table(name="colors") → "colors"(camelToSnake "color" 아님).
        FormFieldBinding binding = FormFieldBinding.reference("color", "x.Color", "x.Color", null, "colors");

        assertThat(EndpointExplorationRunner.resolveBackupTable(binding, List.of(COLORS)))
                .isEqualTo("colors");
    }

    @Test
    void fallsBackToCamelToSnakeWhenNoJoinColumnOrTable() {
        // joinColumn·@Table 없음 → camelToSnake("Brand")="brand" 가 스키마에 있으면 채택.
        FormFieldBinding binding = FormFieldBinding.reference("brand", "x.Brand", "x.Brand", null, null);

        assertThat(EndpointExplorationRunner.resolveBackupTable(binding, List.of(BRAND)))
                .isEqualTo("brand");
    }

    @Test
    void returnsNullWhenUnresolvable() {
        FormFieldBinding binding = FormFieldBinding.reference("widget", "x.Widget", "x.Widget", null, null);

        assertThat(EndpointExplorationRunner.resolveBackupTable(binding, List.of(COLORS))).isNull();
    }
}
