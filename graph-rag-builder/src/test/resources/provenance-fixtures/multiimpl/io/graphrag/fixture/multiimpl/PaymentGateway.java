package io.graphrag.fixture.multiimpl;

/** ProvenanceIndexerIT 픽스처 — 모델 내 구현체가 2개인 인터페이스(REQ-003 MULTI_IMPL 대상). */
public interface PaymentGateway {

    String charge(long amount);
}
