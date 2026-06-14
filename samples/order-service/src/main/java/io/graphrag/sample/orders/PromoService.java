package io.graphrag.sample.orders;

import org.springframework.stereotype.Service;

/**
 * 제약 지향 입력 생성 실증 — 서비스 계층. 분기 리터럴(99, "vip")이 컨트롤러가 아닌
 * 서비스 코드에 있으므로, 전 계층 조건식 추출이어야만 이 값들이 입력으로 환류된다.
 */
@Service
public class PromoService {

    public String classify(Integer score, String tier, String prefix) {
        String suffix = "normal";
        if (score != null && score == 99) {        // 서비스 숫자 동치
            suffix = "jackpot";
        }
        if ("vip".equals(tier)) {                  // 서비스 문자열 동치
            suffix = suffix + "-vip";
        }
        return prefix + ":" + suffix;
    }
}
