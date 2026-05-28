package io.graphrag.builder.staticanalysis.branch;

import io.graphrag.model.Endpoint;
import io.graphrag.model.ExploredPath;
import io.graphrag.model.PathExplorerKind;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps {@link NamedSampleInput}s into shared-model {@link ExploredPath}s.
 *
 * <p>Slug + signature convention matches the legacy {@code path-discovery-static}
 * {@code ExploredPathBuilder} so coverage feedback stays backward compatible:
 * <ul>
 *   <li>{@code id = "static_" + handlerMethod + "_" + slug}</li>
 *   <li>{@code coverageSignature = "static:" + endpoint.id() + ":" + slug}</li>
 *   <li>{@code branchesTaken = [handlerClass + "." + handlerMethod + ":" + slug]}</li>
 *   <li>{@code discoveredBy = PathExplorerKind.MANUAL} (no shared-model change)</li>
 * </ul>
 */
public final class ExploredPathBuilder {

    private ExploredPathBuilder() {}

    public static List<ExploredPath> build(Endpoint endpoint,
                                           List<NamedSampleInput> inputs,
                                           String codeVersion) {
        List<ExploredPath> out = new ArrayList<>(inputs.size());
        for (NamedSampleInput in : inputs) {
            out.add(new ExploredPath(
                    "static_" + endpoint.handlerMethod() + "_" + in.slug(),
                    endpoint.id(),
                    PathExplorerKind.MANUAL,
                    in.input(),
                    /* pathConstraint */ null,
                    List.of(endpoint.handlerClass() + "." + endpoint.handlerMethod() + ":" + in.slug()),
                    in.predictedStatus(),
                    /* exitResponseShape */ null,
                    "static:" + endpoint.id() + ":" + in.slug(),
                    codeVersion));
        }
        return List.copyOf(out);
    }
}
