package io.graphrag.builder.exploration;

import java.util.Objects;

/**
 * 한 path 실행의 coverage 식별자.
 *
 * <p>실 JaCoCo의 line/branch hit set 해시이거나, 단순화된 응답 상태/요약일 수 있음.
 * fuzzer는 같은 signature를 가진 입력을 중복으로 간주.
 */
public final class CoverageSignature {

    private final String hash;

    public CoverageSignature(String hash) {
        this.hash = Objects.requireNonNull(hash, "hash");
    }

    public String hash() { return hash; }

    @Override
    public boolean equals(Object o) {
        return o instanceof CoverageSignature cs && cs.hash.equals(hash);
    }

    @Override
    public int hashCode() { return hash.hashCode(); }

    @Override
    public String toString() { return "cov:" + hash; }
}
