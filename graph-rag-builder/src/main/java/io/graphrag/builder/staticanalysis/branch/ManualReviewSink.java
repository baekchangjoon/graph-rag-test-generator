package io.graphrag.builder.staticanalysis.branch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Consumer of {@link ManualReviewItem}s emitted by branch analysis. Exists as a
 * named functional interface (instead of a raw {@code Consumer<ManualReviewItem>})
 * so that {@link StaticAnalysisPathExplorer} can opt into discarding without
 * surfacing it in the SPI contract.
 */
@FunctionalInterface
public interface ManualReviewSink {

    void accept(ManualReviewItem item);

    /** A sink that collects items into the supplied list (caller-owned). */
    static ManualReviewSink collectingInto(List<ManualReviewItem> bucket) {
        return bucket::add;
    }

    /** A sink that swallows every item — used by the PathExplorer SPI. */
    static ManualReviewSink discarding() {
        return item -> { };
    }

    /**
     * Convenience helper: returns a fresh sink + the backing list as a pair so the
     * caller can grab the items at the end. Returned list is mutable until
     * {@link CollectingSink#frozen()} is called.
     */
    static CollectingSink collecting() {
        return new CollectingSink();
    }

    /** Mutable accumulator returned by {@link #collecting()}. */
    final class CollectingSink implements ManualReviewSink {
        private final List<ManualReviewItem> items = new ArrayList<>();
        @Override public void accept(ManualReviewItem item) { items.add(item); }
        public List<ManualReviewItem> frozen() {
            return Collections.unmodifiableList(new ArrayList<>(items));
        }
    }
}
