package io.graphrag.fixture.recursive;

/** ProvenanceIndexerIT 픽스처 — 상호 재귀(methodA↔methodB) + depth 4 체인(step1..step4). */
public class RecursiveService {

    public String step1() {
        methodA();
        return step2();
    }

    public String step2() {
        return step3();
    }

    public String step3() {
        return step4();
    }

    public String step4() {
        return "done";
    }

    public void methodA() {
        methodB();
    }

    public void methodB() {
        methodA();
    }
}
