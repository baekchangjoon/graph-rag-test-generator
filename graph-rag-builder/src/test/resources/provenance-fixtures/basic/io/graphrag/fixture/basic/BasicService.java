package io.graphrag.fixture.basic;

/** ProvenanceIndexerIT 픽스처 — 핸들러가 위임하는 2번째 클래스(호출그래프 1-hop 대상). */
public class BasicService {

    public String process(int amount) {
        return "OK:" + amount;
    }
}
