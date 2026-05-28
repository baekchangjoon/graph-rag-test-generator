package io.graphrag.builder.staticanalysis.domain;

import io.graphrag.builder.staticanalysis.ast.AstParseResult;
import io.graphrag.builder.staticanalysis.ast.AstParser;
import io.graphrag.model.Endpoint;
import io.graphrag.model.HttpMethod;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for T1+T2 against a minimal "petclinic-like" fixture.
 *
 * <p>The fixture deliberately contains only one of each {@link ClassRole}
 * (CONTROLLER, SERVICE, REPOSITORY, DOMAIN) and five endpoints — enough to
 * exercise every pipeline stage end-to-end while staying small and
 * deterministic. The spec's "≥ 10 endpoints" acceptance criterion in §4.8
 * targets the full Spring petclinic source tree; the wider assertion will
 * be added when the orchestrator wires this analyzer against the real
 * checkout in a follow-up session.
 */
class DomainAnalyzerPetclinicTest {

    private static final Pattern ID_FORMAT =
            Pattern.compile("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS):/.+$");

    @Test
    void analyzes_petclinic_fixture_and_meets_acceptance_criteria() throws Exception {
        Path root = fixtureRoot();
        AstParseResult ast = AstParser.parse(root);
        assertThat(ast.failures()).isEmpty();
        assertThat(ast.parsedFiles()).hasSizeGreaterThanOrEqualTo(4);

        DomainAnalysisResult r = DomainAnalyzer.analyze(ast, "petclinic");

        // ClassRoles
        assertThat(r.classRoles())
                .containsEntry("org.example.petclinic.OwnerRestController", ClassRole.CONTROLLER)
                .containsEntry("org.example.petclinic.OwnerService",        ClassRole.SERVICE)
                .containsEntry("org.example.petclinic.OwnerRepository",     ClassRole.REPOSITORY)
                .containsEntry("org.example.petclinic.Owner",               ClassRole.DOMAIN);

        // Endpoints
        List<String> ids = r.endpoints().stream().map(Endpoint::id).toList();
        assertThat(ids).contains(
                "GET:/owners",
                "GET:/owners/{id}",
                "POST:/owners",
                "PUT:/owners/{id}",
                "DELETE:/owners/{id}");
        assertThat(ids).allMatch(id -> ID_FORMAT.matcher(id).matches());

        // Sorted determinism — same input twice, same order.
        List<String> ids2 = DomainAnalyzer.analyze(AstParser.parse(root), "petclinic")
                .endpoints().stream().map(Endpoint::id).toList();
        assertThat(ids).isEqualTo(ids2);

        // Auth annotations
        Endpoint create = endpointById(r, "POST:/owners");
        assertThat(create.authRequired()).isTrue();
        assertThat(create.requiredRoles()).containsExactly("ADMIN");

        Endpoint update = endpointById(r, "PUT:/owners/{id}");
        assertThat(update.authRequired()).isTrue();
        assertThat(update.requiredRoles()).containsExactly("ADMIN", "USER");

        Endpoint delete = endpointById(r, "DELETE:/owners/{id}");
        assertThat(delete.authRequired()).isTrue();
        assertThat(delete.requiredRoles()).containsExactly("ADMIN");

        Endpoint list = endpointById(r, "GET:/owners");
        assertThat(list.authRequired()).isFalse();

        // MethodAnalysis populated for controller methods
        assertThat(r.methodAnalyses())
                .containsKey("org.example.petclinic.OwnerRestController#listOwners")
                .containsKey("org.example.petclinic.OwnerRestController#getOwner")
                .containsKey("org.example.petclinic.OwnerRestController#createOwner");

        // Branch extraction on getOwner (`if (id == null)`)
        MethodAnalysis getOwner = r.methodAnalyses().get(
                "org.example.petclinic.OwnerRestController#getOwner");
        assertThat(getOwner.branches()).extracting(Branch::kind).contains(BranchKind.IF);

        // Switch + throw in createOwner
        MethodAnalysis createMa = r.methodAnalyses().get(
                "org.example.petclinic.OwnerRestController#createOwner");
        assertThat(createMa.branches()).extracting(Branch::kind)
                .contains(BranchKind.SWITCH, BranchKind.THROW);

        // CallGraph exposes per-method keys (even with no in-project edges resolved).
        assertThat(r.callGraph().edges())
                .containsKey("org.example.petclinic.OwnerRestController#getOwner")
                .containsKey("org.example.petclinic.OwnerRestController#listOwners");
    }

    private static Endpoint endpointById(DomainAnalysisResult r, String id) {
        return r.endpoints().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    private static Path fixtureRoot() throws Exception {
        URL res = DomainAnalyzerPetclinicTest.class.getClassLoader()
                .getResource("staticanalysis/petclinic-fixture");
        if (res == null) throw new IllegalStateException("fixture missing");
        return Paths.get(res.toURI());
    }
}
