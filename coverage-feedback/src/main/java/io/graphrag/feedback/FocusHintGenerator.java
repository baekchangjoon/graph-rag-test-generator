package io.graphrag.feedback;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Computes the hints fed into the NEXT iteration's Stage 1 invocation:
 * <ul>
 *   <li>{@code focus_branches}: the still-missing branch ids — so a future smart
 *       static analyzer could prioritize paths that reach them.</li>
 *   <li>{@code exclude_paths}: endpoint ids whose every variant already had
 *       perfect (or impossible-to-improve) coverage — saves re-emission.</li>
 *   <li>{@code boundary_value_hints}: optional handed back from a future
 *       enhancement; T4's static boundary generator currently doesn't use them.</li>
 * </ul>
 *
 * <p>T5 ships exclude_paths and focus_branches as derived facts from the
 * coverage delta; T4 already accepts {@code --exclude-paths}, so the loop is
 * closed even without changes to T4.
 */
public final class FocusHintGenerator {

    private FocusHintGenerator() {}

    public static NextIterationHints generate(CoverageDelta delta,
                                              List<String> previouslyExcluded) {
        List<String> focusBranches = delta.stillMissing().stream()
                .map(MissingBranch::branchId)
                .toList();
        // We do NOT currently auto-derive new excludes from JaCoCo data — Stage 1
        // doesn't have an inverse map from branch_id back to endpoint_id without
        // crossing analyzer boundaries. So `exclude_paths` only carries forward
        // whatever was already excluded. Hand it through unchanged.
        return new NextIterationHints(
                focusBranches,
                List.copyOf(previouslyExcluded),
                Map.of());
    }

    public record NextIterationHints(
            @JsonProperty("focus_branches")       List<String> focusBranches,
            @JsonProperty("exclude_paths")        List<String> excludePaths,
            @JsonProperty("boundary_value_hints") Map<String, List<String>> boundaryValueHints) {
        public NextIterationHints {
            focusBranches = List.copyOf(focusBranches);
            excludePaths = List.copyOf(excludePaths);
            boundaryValueHints = Map.copyOf(boundaryValueHints);
        }
    }
}
