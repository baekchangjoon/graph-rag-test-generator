package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * REQ-005, AC-4: concolic candidate leaf-name → dot-path 승격 검증.
 */
class CandidateLifterTest {

    private static BodyShape.BodyField field(String name) {
        return new BodyShape.BodyField(name, "java.lang.Object");
    }

    // -----------------------------------------------------------------------
    // TC1: 리프 키를 dot-path로 승격 (핵심 케이스)
    // -----------------------------------------------------------------------
    @Test
    void liftsLeafToDotPath() {
        InputCandidates candidates = new InputCandidates(
                Map.of("min", Set.of(1L, 5L)),
                Map.of(),
                List.of(),
                Map.of(),
                List.of());

        List<BodyShape.BodyField> mutableFields = List.of(
                field("range.min"),
                field("range.max"));

        InputCandidates lifted = CandidateLifter.lift(candidates, mutableFields);

        // "range.min"으로 승격, 원래 "min" 키 제거
        assertThat(lifted.numeric()).containsKey("range.min");
        assertThat(lifted.numeric().get("range.min")).containsExactlyInAnyOrder(1L, 5L);
        assertThat(lifted.numeric()).doesNotContainKey("min");
    }

    // -----------------------------------------------------------------------
    // TC2: 평탄(flat) 필드는 그대로 유지
    // -----------------------------------------------------------------------
    @Test
    void flatUnchanged() {
        InputCandidates candidates = new InputCandidates(
                Map.of("amount", Set.of(100L)),
                Map.of("name", Set.of("alice")),
                List.of(),
                Map.of(),
                List.of());

        List<BodyShape.BodyField> mutableFields = List.of(
                field("amount"),
                field("name"));

        InputCandidates lifted = CandidateLifter.lift(candidates, mutableFields);

        assertThat(lifted.numeric()).containsKey("amount");
        assertThat(lifted.numeric().get("amount")).containsExactly(100L);
        assertThat(lifted.strings()).containsKey("name");
        assertThat(lifted.strings().get("name")).containsExactly("alice");
    }

    // -----------------------------------------------------------------------
    // TC3: 다중 매칭 → 모든 dot-path에 적용
    // -----------------------------------------------------------------------
    @Test
    void multiMatchAppliesToAll() {
        InputCandidates candidates = new InputCandidates(
                Map.of("x", Set.of(7L)),
                Map.of(),
                List.of(),
                Map.of(),
                List.of());

        List<BodyShape.BodyField> mutableFields = List.of(
                field("a.x"),
                field("b.x"));

        InputCandidates lifted = CandidateLifter.lift(candidates, mutableFields);

        assertThat(lifted.numeric()).containsKey("a.x");
        assertThat(lifted.numeric()).containsKey("b.x");
        assertThat(lifted.numeric().get("a.x")).containsExactly(7L);
        assertThat(lifted.numeric().get("b.x")).containsExactly(7L);
        assertThat(lifted.numeric()).doesNotContainKey("x");
    }

    // -----------------------------------------------------------------------
    // TC4: 튜플 — 다중 매칭 키는 승격하지 않음 (애매성 차단)
    // -----------------------------------------------------------------------
    @Test
    void tupleUniqueOnly() {
        // "x"는 다중 매칭(a.x, b.x) → 그대로
        // "y"는 유일 매칭(c.y) → 승격
        Map<String, Long> tuple = Map.of("x", 3L, "y", 9L);
        InputCandidates candidates = new InputCandidates(
                Map.of(),
                Map.of(),
                List.of(tuple),
                Map.of(),
                List.of());

        List<BodyShape.BodyField> mutableFields = List.of(
                field("a.x"),
                field("b.x"),
                field("c.y"));

        InputCandidates lifted = CandidateLifter.lift(candidates, mutableFields);

        assertThat(lifted.tuples()).hasSize(1);
        Map<String, Long> liftedTuple = lifted.tuples().get(0);
        // "x"는 다중 매칭이므로 그대로
        assertThat(liftedTuple).containsKey("x");
        // "y"는 유일 매칭이므로 "c.y"로 승격, 원래 "y" 제거
        assertThat(liftedTuple).containsKey("c.y");
        assertThat(liftedTuple).doesNotContainKey("y");
    }

    // -----------------------------------------------------------------------
    // TC5: 매칭 없는 키는 그대로 (기존 동작 무회귀)
    // -----------------------------------------------------------------------
    @Test
    void unknownKeyKeptAsIs() {
        InputCandidates candidates = new InputCandidates(
                Map.of("orphan", Set.of(42L)),
                Map.of(),
                List.of(),
                Map.of(),
                List.of());

        List<BodyShape.BodyField> mutableFields = List.of(field("amount"));

        InputCandidates lifted = CandidateLifter.lift(candidates, mutableFields);

        assertThat(lifted.numeric()).containsKey("orphan");
        assertThat(lifted.numeric().get("orphan")).containsExactly(42L);
    }

    // -----------------------------------------------------------------------
    // TC6: mutableFields 빈 리스트 → candidates 그대로 반환
    // -----------------------------------------------------------------------
    @Test
    void emptyFieldsReturnsOriginal() {
        InputCandidates candidates = new InputCandidates(
                Map.of("min", Set.of(1L)),
                Map.of(),
                List.of(),
                Map.of(),
                List.of());

        InputCandidates lifted = CandidateLifter.lift(candidates, List.of());

        assertThat(lifted).isSameAs(candidates);
    }

    // -----------------------------------------------------------------------
    // TC7: reals 채널도 승격 검증
    // -----------------------------------------------------------------------
    @Test
    void liftsRealsChannel() {
        InputCandidates candidates = new InputCandidates(
                Map.of(),
                Map.of(),
                List.of(),
                Map.of("score", Set.of(0.5, 1.0)),
                List.of());

        List<BodyShape.BodyField> mutableFields = List.of(field("player.score"));

        InputCandidates lifted = CandidateLifter.lift(candidates, mutableFields);

        assertThat(lifted.reals()).containsKey("player.score");
        assertThat(lifted.reals().get("player.score")).containsExactlyInAnyOrder(0.5, 1.0);
        assertThat(lifted.reals()).doesNotContainKey("score");
    }

    // -----------------------------------------------------------------------
    // TC8: 튜플 유일 매칭 승격 (realTuples 채널)
    // -----------------------------------------------------------------------
    @Test
    void realTupleUniqueMatchLifted() {
        Map<String, Double> tuple = Map.of("ratio", 0.8);
        InputCandidates candidates = new InputCandidates(
                Map.of(),
                Map.of(),
                List.of(),
                Map.of(),
                List.of(tuple));

        List<BodyShape.BodyField> mutableFields = List.of(field("metrics.ratio"));

        InputCandidates lifted = CandidateLifter.lift(candidates, mutableFields);

        assertThat(lifted.realTuples()).hasSize(1);
        assertThat(lifted.realTuples().get(0)).containsKey("metrics.ratio");
        assertThat(lifted.realTuples().get(0)).doesNotContainKey("ratio");
    }
}
