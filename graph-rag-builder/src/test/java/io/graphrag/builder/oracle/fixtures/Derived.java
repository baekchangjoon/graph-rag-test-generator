package io.graphrag.builder.oracle.fixtures;

/** ASM+Z3 concolic 오라클 테스트 픽스처. 분기 리터럴이 소스에 직접 없고 계산으로 도출돼야 한다. */
public class Derived {

    // score*2 == 84 → score=42 (84,2는 리터럴이지만 42는 아님 → 정적 리터럴 추출은 못 풂)
    public int classify(int score) {
        if (score * 2 == 84) {
            return 1;
        }
        return 0;
    }

    // amount + 5 > 100 → amount > 95 (96이 분기 경계, 소스엔 없음)
    public int tier(int amount) {
        if (amount + 5 > 100) {
            return 2;
        }
        return 0;
    }

    // long 파생: bonus*2 == 10000000000 → bonus=5000000000 (int 범위 밖, 소스에 5e9 없음)
    public int bonus(long bonus) {
        if (bonus * 2 == 10000000000L) {
            return 3;
        }
        return 0;
    }

    // 문자열 길이 제약: code.length()==5 → 길이5 문자열 필요 (소스에 그런 리터럴 없음)
    public int codeLen(String code) {
        if (code.length() == 5) {
            return 4;
        }
        return 0;
    }
}
