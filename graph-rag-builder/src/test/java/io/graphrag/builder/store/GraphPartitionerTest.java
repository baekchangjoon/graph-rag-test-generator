package io.graphrag.builder.store;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GraphPartitionerTest {

    @Test
    void partitionOf_isHandlerClassPackage() {
        assertThat(GraphPartitioner.partitionOf("io.graphrag.sample.orders.OrderController"))
                .isEqualTo("io.graphrag.sample.orders");
    }

    @Test
    void partitionOf_defaultPackageFallsBackToClassName() {
        assertThat(GraphPartitioner.partitionOf("OrderController"))
                .isEqualTo("OrderController");
    }

    @Test
    void dirtyPartitions_matchesJavaFileByPackagePath() {
        Set<String> known = Set.of("io.graphrag.sample.orders", "io.graphrag.sample.users");

        Set<String> dirty = GraphPartitioner.dirtyPartitions(
                List.of("samples/order-service/src/main/java/io/graphrag/sample/orders/OrderController.java"),
                known);

        assertThat(dirty).containsExactly("io.graphrag.sample.orders");
    }

    @Test
    void dirtyPartitions_unmappableChangeMarksAllDirty() {
        Set<String> known = Set.of("io.graphrag.sample.orders", "io.graphrag.sample.users");

        Set<String> dirty = GraphPartitioner.dirtyPartitions(
                List.of("samples/order-service/src/main/resources/mappers/OrderMapper.xml"),
                known);

        assertThat(dirty).isEqualTo(known);
    }

    @Test
    void dirtyPartitions_noChangesMeansNothingDirty() {
        assertThat(GraphPartitioner.dirtyPartitions(List.of(),
                Set.of("io.graphrag.sample.orders"))).isEmpty();
    }
}
