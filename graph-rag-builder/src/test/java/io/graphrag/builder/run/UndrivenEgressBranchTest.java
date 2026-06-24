package io.graphrag.builder.run;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ExternalCallSite;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EndpointExplorationRunner#undrivenEgressBranches} 순수 헬퍼 단위 테스트 (REQ-F012-010 loud).
 *
 * <p>검증 항목:
 * <ul>
 *   <li>변형 후보가 존재하고 미등록(span-only)인 site → {@code egress-branch-undriven} loud-fail 생성</li>
 *   <li>변형 후보가 존재하더라도 이미 등록된 site → loud-fail 없음</li>
 * </ul>
 */
class UndrivenEgressBranchTest {

    /** region 필드(String)를 가진 InventoryResponse 형상 */
    private static final BodyShape SHAPE_WITH_STRING_REGION = new BodyShape(
            "io.x.InventoryResponse",
            List.of(new BodyShape.BodyField("region", "java.lang.String")));

    @Test
    void flagsUndriven_whenCandidatesExistButNotRegistered() {
        var site = new ExternalCallSite("GET", "/inventory/stock", Optional.of(SHAPE_WITH_STRING_REGION));
        var literals = Map.of("io.x.InventoryResponse", Map.of("region", List.of("EMBARGOED")));
        var loud = EndpointExplorationRunner.undrivenEgressBranches(
                List.of(site), literals, Map.of(), (m, p) -> false);   // 미등록 = span-only
        assertThat(loud).anyMatch(lf -> lf.reason().equals("egress-branch-undriven")
                && lf.target().equals("GET /inventory/stock"));
    }

    @Test
    void noFlag_whenRegistered() {
        var site = new ExternalCallSite("GET", "/inventory/stock", Optional.of(SHAPE_WITH_STRING_REGION));
        var literals = Map.of("io.x.InventoryResponse", Map.of("region", List.of("EMBARGOED")));
        assertThat(EndpointExplorationRunner.undrivenEgressBranches(
                List.of(site), literals, Map.of(), (m, p) -> true)).isEmpty();
    }
}
