package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.IndexResult;
import io.graphrag.builder.index.ValidationConstraintExtractor;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.model.Endpoint;
import io.graphrag.model.ParamKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.reflect.CtModel;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * LLM 값 오라클 — 엔드포인트별 핸들러 소스+제약으로 도메인 그럴듯한 문자열 값을 생성해
 * {@link InputCandidates}의 strings 채널에만 기여한다(union 추가 멤버). 결정성은 캐시가 보장하고,
 * API 키 없음/실패는 best-effort로 삼켜 빌드를 깨지 않는다.
 */
public final class LlmOracle implements InputOracle {
    private static final Logger log = LoggerFactory.getLogger(LlmOracle.class);

    private final IndexResult index;
    private final ValidationConstraintExtractor valid;
    private final HandlerSourceExtractor handlerSrc;
    private final LlmValueClient client;
    private final LlmValueCache cache;
    private final String modelId;
    private final boolean clientUsable;
    /** 탐색 단계가 주입한 공유 Spoon 모델(null이면 analyze에서 sut.roots()로 빌드). */
    private final CtModel sharedModel;

    public LlmOracle(IndexResult index, ValidationConstraintExtractor valid,
                     HandlerSourceExtractor handlerSrc, LlmValueClient client,
                     LlmValueCache cache, String modelId, boolean clientUsable) {
        this(index, valid, handlerSrc, client, cache, modelId, clientUsable, null);
    }

    /** 공유 모델 주입 — validation 추출이 엔드포인트마다 재빌드하지 않고 단일 모델 재사용. */
    public LlmOracle(IndexResult index, ValidationConstraintExtractor valid,
                     HandlerSourceExtractor handlerSrc, LlmValueClient client,
                     LlmValueCache cache, String modelId, boolean clientUsable, CtModel sharedModel) {
        this.index = index;
        this.valid = valid;
        this.handlerSrc = handlerSrc;
        this.client = client;
        this.cache = cache;
        this.modelId = modelId;
        this.clientUsable = clientUsable;
        this.sharedModel = sharedModel;
    }

    @Override
    public String name() {
        return "llm";
    }

    @Override
    public InputCandidates analyze(SutCode sut) {
        InputCandidates acc = InputCandidates.empty();
        for (Endpoint ep : index.endpoints()) {
            BodyShape bodyShape = bodyShapeFor(ep, index.bodyShapes());
            // 입력면 = 바디 필드(BODY/FORM) ∪ PATH/QUERY 파라미터(read 엔드포인트 포함). 둘 다 없으면 skip.
            BodyShape shape = effectiveShape(ep, bodyShape);
            if (shape == null) {
                continue;
            }
            // 제약(@Pattern/@Email)은 DTO/커맨드 바디 타입에서만 추출 가능. 파라미터는 도메인코드 이름 휴리스틱으로 선별.
            Map<String, List<FieldConstraint>> constraints =
                    bodyShape == null ? Map.of()
                            : sharedModel != null ? valid.extract(sharedModel, bodyShape.javaType())
                            : valid.extract(sut.roots(), bodyShape.javaType());
            EndpointFieldSelector.Selected selected =
                    EndpointFieldSelector.select(shape.fields(), constraints);
            if (selected.fields().isEmpty()) {
                continue;
            }
            String body = handlerSrc.extract(ep.handlerClass(), ep.handlerMethod());
            String key = LlmValueCache.key(ep.id(), body, selected.fields(), modelId);
            LlmFieldValues vals = cache.read(key).orElse(null);
            if (vals == null) {
                if (!clientUsable) {
                    log.info("llm oracle: cache miss + client unusable (no API key) → skip {}",
                            ep.id());
                    continue;
                }
                try {
                    vals = client.generate(new LlmRequest(ep.id(), body, selected.fields(),
                            selected.patternByField(), selected.emailFields(), modelId));
                    cache.write(key, vals);
                } catch (Exception e) {
                    log.warn("llm oracle: generate failed for {} → skip: {}",
                            ep.id(), e.getMessage());
                    continue;
                }
            }
            Map<String, Set<String>> gated = ShapeGate.filter(vals, shape);
            if (!gated.isEmpty()) {
                acc = acc.merge(new InputCandidates(Map.of(), gated));
            }
        }
        return acc;
    }

    private static BodyShape bodyShapeFor(Endpoint ep, Map<String, BodyShape> shapes) {
        return ep.params().stream()
                .filter(p -> p.kind() == ParamKind.BODY || p.kind() == ParamKind.FORM)
                .map(p -> shapes.get(p.javaType()))
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
    }

    /** 선별·그라운딩용 유효 입력면 = 바디 필드 ∪ PATH/QUERY 파라미터(이름 dedup). 필드 0이면 null. */
    private static BodyShape effectiveShape(Endpoint ep, BodyShape bodyShape) {
        java.util.LinkedHashMap<String, BodyShape.BodyField> byName = new java.util.LinkedHashMap<>();
        if (bodyShape != null) {
            for (BodyShape.BodyField f : bodyShape.fields()) {
                byName.put(f.name(), f);
            }
        }
        for (var p : ep.params()) {
            if (p.kind() == ParamKind.PATH || p.kind() == ParamKind.QUERY) {
                byName.putIfAbsent(p.name(), new BodyShape.BodyField(p.name(), p.javaType()));
            }
        }
        if (byName.isEmpty()) {
            return null;
        }
        String javaType = bodyShape != null ? bodyShape.javaType() : "params:" + ep.id();
        return new BodyShape(javaType, new java.util.ArrayList<>(byName.values()), false);
    }
}
