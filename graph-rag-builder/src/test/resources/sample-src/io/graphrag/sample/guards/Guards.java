package io.graphrag.sample.guards;

public class Guards {
    enum Tier { BASIC, VIP }

    record Req(Tier tier, int loyalty, int nights, String code) { }

    String check(Req req) {
        // 다필드 && (enum + numeric) — 추출 대상
        if (req.tier() == Tier.VIP && req.loyalty() < 500) {
            return "vip-low";
        }
        // 단일필드 || (범위) — conjunction 아님(제외)
        if (req.nights() < 1 || req.nights() > 30) {
            return "nights";
        }
        // 중첩 && 3원자(2필드) + 문자열 동치 — 평탄화 대상
        if (req.tier() == Tier.BASIC && req.code().equals("X") && req.loyalty() > 10) {
            return "combo";
        }
        return "ok";
    }
}
