package io.graphrag.builder.oracle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RationalTest {

    @Test
    void ofLongIsIntegerDenOne() {
        assertThat(Rational.of(5L).num()).isEqualTo(5);
        assertThat(Rational.of(5L).den()).isEqualTo(1);
        assertThat(Rational.of(5L).isInteger()).isTrue();
    }

    @Test
    void ofDoubleSmallDenominator() {
        assertThat(Rational.of(2.0)).isEqualTo(new Rational(2, 1));
        assertThat(Rational.of(0.5)).isEqualTo(new Rational(1, 2));
        assertThat(Rational.of(3.0)).isEqualTo(new Rational(3, 1));
        assertThat(Rational.of(-1.5)).isEqualTo(new Rational(-3, 2));
    }

    @Test
    void constructorReducesAndNormalizesSign() {
        assertThat(new Rational(4, 8)).isEqualTo(new Rational(1, 2));
        assertThat(new Rational(2, -4)).isEqualTo(new Rational(-1, 2));   // den>0 정규화
        assertThat(new Rational(-6, -9)).isEqualTo(new Rational(2, 3));
    }

    @Test
    void arithmetic() {
        // 1/2 + 1/3 = 5/6
        assertThat(Rational.of(1).mul(new Rational(1, 2)).add(new Rational(1, 3)))
                .isEqualTo(new Rational(5, 6));
        // 2*48.5 + 3*1 = 100 (벤치마크 등식 게이트 검산)
        Rational lhs = Rational.of(2).mul(new Rational(97, 2)).add(Rational.of(3).mul(Rational.of(1)));
        assertThat(lhs).isEqualTo(Rational.of(100));
        assertThat(new Rational(3, 2).sub(new Rational(1, 2))).isEqualTo(Rational.ONE);
        assertThat(Rational.of(5).neg()).isEqualTo(new Rational(-5, 1));
    }

    @Test
    void toDoubleRoundTrips() {
        assertThat(new Rational(1, 4).toDouble()).isEqualTo(0.25);
        assertThat(Rational.of(100).toDouble()).isEqualTo(100.0);
    }

    @Test
    void overflowThrowsForBailHandling() {
        // 호출자(Sym)가 잡아 top으로 bail하는 신호.
        Rational big = new Rational(Long.MAX_VALUE / 2, 1);
        assertThatThrownBy(() -> big.mul(big)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void nonFiniteRejected() {
        assertThatThrownBy(() -> Rational.of(Double.NaN)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Rational.of(Double.POSITIVE_INFINITY)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> new Rational(1, 0)).isInstanceOf(ArithmeticException.class);
    }
}
