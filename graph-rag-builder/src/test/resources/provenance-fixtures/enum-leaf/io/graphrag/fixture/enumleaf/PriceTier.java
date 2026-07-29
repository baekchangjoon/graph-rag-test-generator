package io.graphrag.fixture.enumleaf;

/**
 * 파생 getter를 가진 enum — petclinic `PriceTier`와 동일 형태. 이걸 DTO처럼 전개하면
 * body가 {@code "priceTier":{"nightlyRate":…}}가 되어 Jackson이 enum에 객체를 매핑하지 못해
 * 400이 확정된다(E2E-B2 실증 #1의 petclinic 차단 원인 A).
 */
public enum PriceTier {

    BASIC(100),
    PREMIUM(200),
    VIP(500);

    private final int nightlyRate;

    PriceTier(int nightlyRate) {
        this.nightlyRate = nightlyRate;
    }

    public int getNightlyRate() {
        return nightlyRate;
    }
}
