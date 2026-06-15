package io.graphrag.builder.oracle;

/**
 * overflow-안전 유리수(num/den, long). ConcolicOracle 선형식의 계수 표현 — 정수는 den=1로 표현돼
 * 기존 정수 경로가 보존되고(Rational.of(long)), float/double 상수·계수는 정확한 분수로 모델링된다
 * (Z3 mkReal(num,den)에 그대로 내려 double 반올림을 피한다).
 *
 * <p>모든 산술은 {@link Math#multiplyExact}/{@link Math#addExact}로 overflow를 감지해 {@link ArithmeticException}
 * 을 던진다 — 호출자(ConcolicOracle.Sym)는 이를 잡아 top으로 bail(거짓 양성 입력 방지, 3-모델 리뷰 GPT I3).
 * 생성 시 gcd 약분 + den&gt;0 정규화로 표준형을 유지한다.
 */
record Rational(long num, long den) {

    static final Rational ZERO = new Rational(0, 1);
    static final Rational ONE = new Rational(1, 1);

    Rational {
        if (den == 0) {
            throw new ArithmeticException("rational denominator zero");
        }
        // den>0 정규화
        if (den < 0) {
            num = Math.negateExact(num);
            den = Math.negateExact(den);
        }
        long g = gcd(Math.abs(num), den);
        if (g > 1) {
            num /= g;
            den /= g;
        }
    }

    static Rational of(long v) {
        return new Rational(v, 1);
    }

    /** double/float 상수 → 정확 분수. 표준 십진(BigDecimal.valueOf)로 작은 분모 유지(2.0→2/1, 0.5→1/2). */
    static Rational of(double v) {
        if (!Double.isFinite(v)) {
            throw new ArithmeticException("non-finite rational: " + v);
        }
        java.math.BigDecimal bd = java.math.BigDecimal.valueOf(v).stripTrailingZeros();
        int scale = bd.scale();
        if (scale <= 0) {
            // 정수: unscaled * 10^-scale
            java.math.BigInteger n = bd.unscaledValue().multiply(java.math.BigInteger.TEN.pow(-scale));
            return new Rational(n.longValueExact(), 1);
        }
        long n = bd.unscaledValue().longValueExact();
        long d = java.math.BigInteger.TEN.pow(scale).longValueExact();
        return new Rational(n, d);
    }

    boolean isZero() {
        return num == 0;
    }

    boolean isInteger() {
        return den == 1;
    }

    Rational add(Rational o) {
        // a/b + c/d = (a*d + c*b) / (b*d)
        long n = Math.addExact(Math.multiplyExact(num, o.den), Math.multiplyExact(o.num, den));
        long d = Math.multiplyExact(den, o.den);
        return new Rational(n, d);
    }

    Rational sub(Rational o) {
        long n = Math.subtractExact(Math.multiplyExact(num, o.den), Math.multiplyExact(o.num, den));
        long d = Math.multiplyExact(den, o.den);
        return new Rational(n, d);
    }

    Rational mul(Rational o) {
        return new Rational(Math.multiplyExact(num, o.num), Math.multiplyExact(den, o.den));
    }

    Rational neg() {
        return new Rational(Math.negateExact(num), den);
    }

    double toDouble() {
        return (double) num / (double) den;
    }

    private static long gcd(long a, long b) {
        while (b != 0) {
            long t = b;
            b = a % b;
            a = t;
        }
        return a == 0 ? 1 : a;
    }
}
