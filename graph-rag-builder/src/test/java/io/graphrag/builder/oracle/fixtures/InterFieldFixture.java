package io.graphrag.builder.oracle.fixtures;

/** Stage 4 inter-field 솔버 픽스처: 2-필드 선형은 풀고, 3+필드·진짜 곱은 bail. */
public class InterFieldFixture {

    // 2-필드 선형 등식: loyaltyPoints == nights*17 → 튜플 (17,1)은 solver만 도출(소스에 (17,1) 없음).
    public int gate(int loyaltyPoints, int nights) {
        if (loyaltyPoints != nights * 17) {
            return 0;
        }
        return 1;
    }

    // 3-필드 선형 → BAIL (cap 2): 어떤 튜플도 내지 않는다.
    public int three(int a, int b, int c) {
        if (a + b + c == 100) {
            return 1;
        }
        return 0;
    }

    // 진짜 곱 a*b → BAIL (비선형): 튜플 없음.
    public int product(int a, int b) {
        if (a * b == 50) {
            return 1;
        }
        return 0;
    }
}
