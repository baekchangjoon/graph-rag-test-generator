package io.graphrag.builder.cli;

import io.graphrag.builder.index.ConstraintExtractor;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BuilderCli.isReachable 헬퍼 단위 테스트 (REQ-012, REQ-014).
 * 귀속 필터 로직을 package-private 정적 헬퍼로 분리해 단위 테스트 가능하게 설계.
 */
class BuilderCliAttributionTest {

    // --- 헬퍼 메서드 ---

    private static Map.Entry<String, String> entry(String type, String method) {
        return new AbstractMap.SimpleEntry<>(type, method);
    }

    private static ConstraintExtractor.StateGuard stateGuard(String classFqn, String method) {
        return new ConstraintExtractor.StateGuard(
                classFqn, method, 1, "col",
                ConstraintExtractor.GuardKind.BOOLEAN, null,
                java.util.List.of(), java.util.List.of());
    }

    private static ConstraintExtractor.JoinGuard joinGuard(String classFqn, String method) {
        return new ConstraintExtractor.JoinGuard(
                classFqn, method, 1, "leftRef", "==", "rightRef",
                ConstraintExtractor.JoinKind.NUMERIC);
    }

    // --- REQ-012: reachable 기반 StateGuard 귀속 ---

    /**
     * REQ-012: 서비스 메서드에 가드가 있고 핸들러가 그 메서드를 1-hop 호출 → 귀속됨.
     */
    @Test
    void reachableIncludesServiceGuard() {
        // reachable = 핸들러 자신 + 서비스 메서드
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "getById"),
                entry("com.example.ReservationService", "getById")
        );
        // 가드는 서비스 메서드에 위치
        boolean result = BuilderCli.isReachable(
                reachable, "com.example.ReservationService", "getById");

        assertThat(result).isTrue();
    }

    /**
     * REQ-012: reachable에 없는 (다른 서비스) 가드 → 귀속 안 됨.
     */
    @Test
    void unreachableExcluded() {
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "getById"),
                entry("com.example.ReservationService", "getById")
        );
        // 가드는 reachable에 없는 다른 클래스
        boolean result = BuilderCli.isReachable(
                reachable, "com.example.OtherService", "getById");

        assertThat(result).isFalse();
    }

    /**
     * REQ-012: reachable에 없는 동명 메서드라도 클래스가 다르면 귀속 안 됨.
     */
    @Test
    void sameMethodNameDifferentClassExcluded() {
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "save"),
                entry("com.example.ReservationService", "save")
        );
        // 완전히 다른 패키지의 같은 이름 메서드
        boolean result = BuilderCli.isReachable(
                reachable, "com.other.AuditService", "save");

        assertThat(result).isFalse();
    }

    /**
     * REQ-012: 핸들러 자신에 가드가 있는 기존 케이스도 reachable에 포함되어 귀속됨 (회귀 안전).
     */
    @Test
    void handlerSelfIsReachable() {
        // reachable에는 핸들러 자신이 항상 포함 (ConstraintExtractor.reachableMethods 보장)
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "deleteById")
        );
        boolean result = BuilderCli.isReachable(
                reachable, "com.example.BookingController", "deleteById");

        assertThat(result).isTrue();
    }

    // --- REQ-012: JoinGuard도 동일 reachable 귀속 ---

    /**
     * REQ-012: JoinGuard도 reachable 기반으로 귀속됨.
     */
    @Test
    void joinGuardReachable() {
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "list"),
                entry("com.example.ReservationService", "findAll")
        );
        ConstraintExtractor.JoinGuard guard = joinGuard("com.example.ReservationService", "findAll");

        boolean result = BuilderCli.isReachable(reachable, guard.classFqn(), guard.method());

        assertThat(result).isTrue();
    }

    // --- REQ-006: conjunction(StateGuardConjunction) cross-class 귀속 ---

    private static ConstraintExtractor.StateGuardConjunction conjunction(String classFqn, String method) {
        return new ConstraintExtractor.StateGuardConjunction(
                classFqn, method, 10,
                java.util.List.of(stateGuard(classFqn, method)));
    }

    /**
     * REQ-006: conjunction이 서비스 메서드에 있고 핸들러가 1-hop 호출 → 그 엔드포인트에 귀속됨.
     * (StateGuard·JoinGuard와 동일하게 classFqn/method 기반 isReachable 필터를 재사용.)
     */
    @Test
    void conjunctionReachable() {
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "premiumEligible"),
                entry("com.example.BookingService", "isPremiumEligible")
        );
        ConstraintExtractor.StateGuardConjunction conj =
                conjunction("com.example.BookingService", "isPremiumEligible");

        boolean result = BuilderCli.isReachable(reachable, conj.classFqn(), conj.method());

        assertThat(result).isTrue();
    }

    // --- REQ-008: GRB_STATE_GUARDS ablation 게이트 ---

    /**
     * REQ-008: GRB_STATE_GUARDS=off(대소문자 무시)면 비활성 → allStateGuards·allStateGuardConjunctions
     * 모두 빈 리스트(변종 no-op). null/미설정/그 외 값은 활성. state-guard와 conjunction이 동일 게이트 사용.
     */
    @Test
    void stateGuardsAblationGate() {
        assertThat(BuilderCli.stateGuardsEnabled("off")).isFalse();
        assertThat(BuilderCli.stateGuardsEnabled("OFF")).isFalse();
        assertThat(BuilderCli.stateGuardsEnabled(null)).isTrue();
        assertThat(BuilderCli.stateGuardsEnabled("on")).isTrue();
        assertThat(BuilderCli.stateGuardsEnabled("")).isTrue();
    }

    /**
     * REQ-006: conjunction이 reachable에 없는 클래스면 귀속 안 됨.
     */
    @Test
    void conjunctionUnreachableExcluded() {
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "premiumEligible")
        );
        ConstraintExtractor.StateGuardConjunction conj =
                conjunction("com.example.OtherService", "isPremiumEligible");

        boolean result = BuilderCli.isReachable(reachable, conj.classFqn(), conj.method());

        assertThat(result).isFalse();
    }

    /**
     * REQ-012: JoinGuard가 reachable에 없으면 귀속 안 됨.
     */
    @Test
    void joinGuardUnreachableExcluded() {
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "list")
        );
        ConstraintExtractor.JoinGuard guard = joinGuard("com.example.OtherService", "findAll");

        boolean result = BuilderCli.isReachable(reachable, guard.classFqn(), guard.method());

        assertThat(result).isFalse();
    }

    // --- simpleName endsWith 폴백 (noClasspath 인터페이스 FQN 미해소 케이스) ---

    /**
     * REQ-012: noClasspath 모드에서 reachable의 타입 FQN이 인터페이스 simpleName으로만 해소된 경우
     * (예: reachable에 "ReservationService"만 있고 가드는 "com.example.ReservationService") →
     * endsWith 폴백으로 귀속됨.
     */
    @Test
    void simpleNameFallbackMatchesWhenFqnEndsWith() {
        // noClasspath: reachable 엔트리가 simpleName만 있는 경우
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "getById"),
                entry("ReservationService", "getById")   // simpleName만 (미해소)
        );
        // 가드의 classFqn은 정확한 FQN
        boolean result = BuilderCli.isReachable(
                reachable, "com.example.ReservationService", "getById");

        assertThat(result).isTrue();
    }

    /**
     * REQ-012: 반대로 가드 classFqn이 simpleName이고 reachable이 FQN인 경우도 폴백으로 귀속.
     */
    @Test
    void simpleNameFallbackMatchesWhenGuardClassIsSimpleName() {
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.ReservationService", "getById")
        );
        // 가드 classFqn이 simpleName으로만 기록된 경우 (이론적으로 가능)
        boolean result = BuilderCli.isReachable(
                reachable, "ReservationService", "getById");

        // "com.example.ReservationService".endsWith(".ReservationService") → true
        assertThat(result).isTrue();
    }

    // --- REQ-014: pass-through 동명 파라미터 매칭 ---

    /**
     * REQ-014: NUMERIC-vs-파라미터 가드의 comparand(서비스 파라미터명)가
     * 엔드포인트 QUERY/PATH 파라미터명과 동명일 때 매칭.
     * isReachable 헬퍼 자체는 pass-through를 다루지 않음 — ReadInputSynthesizer에서 처리.
     * 여기서는 동명 comparand를 가진 가드가 reachable 귀속된 후 synthesizeVariants에서
     * 처리 가능함을 간접 검증한다.
     * (동명 pass-through는 별도 코드 변경 없이 base.body().get(P)로 자동 동작한다.)
     */
    @Test
    void passThroughSameNameGuardIsReachable() {
        // 핸들러 BookingController.list가 ReservationService.findByNights(minNights)를 호출
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "list"),
                entry("com.example.ReservationService", "findByNights")
        );
        // 서비스 메서드의 NUMERIC-vs-파라미터 가드 (comparand="minNights")
        ConstraintExtractor.StateGuard guard = new ConstraintExtractor.StateGuard(
                "com.example.ReservationService", "findByNights", 10, "nights",
                ConstraintExtractor.GuardKind.NUMERIC, null,
                java.util.List.of(), java.util.List.of(),
                ">=", ConstraintExtractor.ComparandKind.PARAM, "minNights");

        // reachable 귀속 확인
        boolean reachable_result = BuilderCli.isReachable(
                reachable, guard.classFqn(), guard.method());
        assertThat(reachable_result).isTrue();

        // comparand가 파라미터명 "minNights" — 엔드포인트가 동명 QUERY param을 가지면
        // ReadInputSynthesizer가 base.body().get("minNights")로 값을 찾아 처리한다.
        assertThat(guard.comparandKind()).isEqualTo(ConstraintExtractor.ComparandKind.PARAM);
        assertThat(guard.comparand()).isEqualTo("minNights");
    }
}
