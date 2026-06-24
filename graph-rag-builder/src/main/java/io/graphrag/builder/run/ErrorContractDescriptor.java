package io.graphrag.builder.run;

import io.graphrag.builder.oracle.ClassifierConfig;

import java.util.List;

/**
 * 에러 envelope SUT의 계약 기술자.
 * errorWhenPresent 트리거 필드가 하나 이상 설정된 경우에만 생성한다.
 */
public record ErrorContractDescriptor(
        List<String> errorWhenPresent,
        String semanticStatusField,
        String errorDetailField,
        String errorDetailContains) {

    /**
     * ClassifierConfig에서 ErrorContractDescriptor를 파생한다.
     * errorWhenPresent가 null이거나 비어있으면 null 반환(envelope 미적용).
     * ClassifierConfig.from()이 semanticStatusField를 기본 "errorCode"로 채우더라도
     * 게이트는 errorWhenPresent 비어있음 여부로만 판단한다.
     */
    public static ErrorContractDescriptor fromClassifierConfig(ClassifierConfig c) {
        if (c == null || c.errorWhenPresent() == null || c.errorWhenPresent().isEmpty()) {
            return null;
        }
        return new ErrorContractDescriptor(c.errorWhenPresent(), c.semanticStatusField(),
                c.errorDetailField(), c.errorDetailContains());
    }
}
