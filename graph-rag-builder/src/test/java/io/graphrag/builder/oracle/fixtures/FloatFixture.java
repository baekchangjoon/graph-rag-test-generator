package io.graphrag.builder.oracle.fixtures;

/**
 * float/double inter-field 솔버 픽스처(작업 #4). 정수 픽스처(InterFieldFixture)의 float 대응:
 * 단일 경계·2-필드 선형 튜플은 Real로 풀고, 변수×변수·int↔float 혼합은 bail.
 */
public class FloatFixture {

    // 단일 float 필드 경계: price >= 100.0f → 경계 100 부근 real 후보(소스에 100은 있으나 real 채널 검증).
    public int singleFloatBoundary(float price) {
        if (price >= 100.0f) {
            return 1;
        }
        return 0;
    }

    // 단일 double 필드 경계: rate >= 1.5 → 경계 1.5 부근 real 후보(DCMP 경로).
    public int doubleBoundary(double rate) {
        if (rate >= 1.5) {
            return 1;
        }
        return 0;
    }

    // 두 float 필드 순수 선형 inter-field: base*2 + surcharge*3 가 band [99.5,100.5] → real 튜플.
    // 필드별 후보로는 동시충족 불가 — Real solveTuple만 도출.
    public int twoFieldBand(float base, float surcharge) {
        float combined = base * 2.0f + surcharge * 3.0f;
        if (combined < 99.5f) {
            return 0;
        }
        if (combined > 100.5f) {
            return 0;
        }
        return 1;
    }

    // 객체 파라미터 + float 접근자 경로(E2E PricingController와 동형): objectParam → field(real).
    public int viaAccessor(Quote q) {
        float combined = q.base() * 2.0f + q.surcharge() * 3.0f;
        if (combined < 99.5f) {
            return 0;
        }
        if (combined > 100.5f) {
            return 0;
        }
        return 1;
    }

    // 변수×변수 곱 → BAIL (비선형): real 튜플 없음.
    public int product(float a, float b) {
        if (a * b >= 50.0f) {
            return 1;
        }
        return 0;
    }

    // int↔float 혼합 비교 → BAIL (origin 추적): 정수 qty 와 float price 를 한 식에 → MIXED → 튜플 없음.
    public int mixed(int qty, float price) {
        if (qty + price >= 10.0f) {
            return 1;
        }
        return 0;
    }

    public record Quote(float base, float surcharge) {
    }
}
