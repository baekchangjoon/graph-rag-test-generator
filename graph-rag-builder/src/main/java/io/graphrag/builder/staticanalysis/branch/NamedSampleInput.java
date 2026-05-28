package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.model.SampleInput;

import java.util.Objects;

/**
 * Internal carrier emitted by {@link SampleInputGenerator}. Pairs a {@link SampleInput}
 * with the slug + predicted HTTP status that {@link ExploredPathBuilder} needs to
 * compose an {@code ExploredPath}.
 *
 * @param slug             variant tag (e.g. {@code "happy"}, {@code "id-neg1"})
 * @param predictedStatus  expected HTTP response status code
 * @param input            populated {@link SampleInput}
 */
record NamedSampleInput(String slug, int predictedStatus, SampleInput input) {

    NamedSampleInput {
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(input, "input");
    }
}
