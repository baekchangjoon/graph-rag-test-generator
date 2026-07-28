package io.graphrag.fixture.impldispatch;

/** ProvenanceIndexerIT 픽스처 — 구현체 2개(모호한 디스패치). */
public interface SocialVerifier {

    String verify(String provider, String providerToken);
}
