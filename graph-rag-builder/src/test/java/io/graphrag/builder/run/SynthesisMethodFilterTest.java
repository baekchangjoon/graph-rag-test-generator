package io.graphrag.builder.run;

import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SynthesisMethodFilterTest {

    @Test
    void parse_commaSeparatedFqnEntries() {
        Set<Map.Entry<String, String>> parsed = SynthesisMethodFilter.parse(
                "com.example.PaymentService#charge, ReservationService#list");

        assertThat(parsed).containsExactly(
                entry("com.example.PaymentService", "charge"),
                entry("ReservationService", "list"));
    }

    @Test
    void parse_ignoresInvalidTokens() {
        assertThat(SynthesisMethodFilter.parse("badtoken,com.x.Foo#bar"))
                .containsExactly(entry("com.x.Foo", "bar"));
    }

    @Test
    void parse_dotWildcardSyntax() {
        Set<Map.Entry<String, String>> parsed = SynthesisMethodFilter.parse(
                "com.example.PaymentService.*, ReservationService#*");

        assertThat(parsed).containsExactly(
                entry("com.example.PaymentService", "*"),
                entry("ReservationService", "*"));
    }

    @Test
    void matches_classWildcard_excludesAnyMethodOnType() {
        Set<Map.Entry<String, String>> exclude = Set.of(entry("PaymentService", "*"));

        assertThat(SynthesisMethodFilter.matches(exclude,
                "com.example.PaymentService", "charge")).isTrue();
        assertThat(SynthesisMethodFilter.matches(exclude,
                "com.example.PaymentService", "refund")).isTrue();
        assertThat(SynthesisMethodFilter.matches(exclude,
                "com.example.ReservationService", "charge")).isFalse();
    }

    @Test
    void reachableTouchesExcluded_classWildcardMatchesAnyReachableMethod() {
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "create"),
                entry("com.example.PaymentService", "authorize"));

        assertThat(SynthesisMethodFilter.reachableTouchesExcluded(reachable,
                SynthesisMethodFilter.parse("PaymentService.*"))).isTrue();
    }

    @Test
    void matches_simpleNameAgainstFqn() {
        Set<Map.Entry<String, String>> exclude = Set.of(entry("ReservationService", "list"));

        assertThat(SynthesisMethodFilter.matches(exclude,
                "com.example.ReservationService", "list")).isTrue();
        assertThat(SynthesisMethodFilter.matches(exclude,
                "com.example.PaymentService", "list")).isFalse();
    }

    @Test
    void reachableTouchesExcluded_whenHandlerCallsExcludedService() {
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "getById"),
                entry("com.example.PaymentService", "charge"));
        Set<Map.Entry<String, String>> exclude = Set.of(entry("PaymentService", "charge"));

        assertThat(SynthesisMethodFilter.reachableTouchesExcluded(reachable, exclude)).isTrue();
    }

    @Test
    void reachableTouchesExcluded_falseWhenNoOverlap() {
        Set<Map.Entry<String, String>> reachable = Set.of(
                entry("com.example.BookingController", "getById"),
                entry("com.example.ReservationService", "list"));
        Set<Map.Entry<String, String>> exclude = Set.of(entry("PaymentService", "charge"));

        assertThat(SynthesisMethodFilter.reachableTouchesExcluded(reachable, exclude)).isFalse();
    }

    private static Map.Entry<String, String> entry(String type, String method) {
        return new AbstractMap.SimpleEntry<>(type, method);
    }
}
