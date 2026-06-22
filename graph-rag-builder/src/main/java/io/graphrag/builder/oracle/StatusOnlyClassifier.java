package io.graphrag.builder.oracle;

import com.fasterxml.jackson.databind.JsonNode;
import io.graphrag.model.Outcome;

/**
 * 상태 코드만으로 성공 여부를 판정하는 기본 ResponseClassifier.
 * wireStatus / 100 == 2 이면 SUCCESS, 그 외 FAILURE.
 * 기존 탐색기의 암묵적 판정(status/100==2 → 성공)을 명시적 구현으로 보존한다.
 */
public final class StatusOnlyClassifier implements ResponseClassifier {

    @Override
    public Outcome classify(int wireStatus, JsonNode body) {
        return wireStatus / 100 == 2
                ? Outcome.success(wireStatus)
                : new Outcome(Outcome.Kind.FAILURE, wireStatus, String.valueOf(wireStatus), "status");
    }
}
