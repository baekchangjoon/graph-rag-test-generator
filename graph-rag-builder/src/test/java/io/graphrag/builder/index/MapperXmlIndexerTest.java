package io.graphrag.builder.index;

import io.graphrag.model.MapperStatement;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MapperXmlIndexerTest {

    @Test
    void index_findsDynamicSelectStatement() {
        List<MapperStatement> statements =
                new MapperXmlIndexer().index(Path.of("src/test/resources/sample-src"));

        assertThat(statements).hasSize(1);
        MapperStatement statement = statements.get(0);
        assertThat(statement.namespace()).isEqualTo("io.graphrag.sample.orders.OrderSearchMapper");
        assertThat(statement.statementId()).isEqualTo("search");
        assertThat(statement.sqlKind()).isEqualTo("SELECT");
        assertThat(statement.dynamic()).isTrue();
        assertThat(statement.id()).isEqualTo("mapper-ordersearchmapper-search");
        assertThat(statement.sourceXml()).contains("<if test=\"userId != null\">");
    }

    @Test
    void index_directoryWithoutMappers_returnsEmpty() {
        List<MapperStatement> statements =
                new MapperXmlIndexer().index(Path.of("src/test/resources/sample-src/io"));
        assertThat(statements).isEmpty();
    }
}
