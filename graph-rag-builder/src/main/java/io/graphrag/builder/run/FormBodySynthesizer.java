package io.graphrag.builder.run;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.BodyShape.BodyField;
import io.graphrag.builder.index.FormFieldBinding;
import io.graphrag.builder.index.FormFieldBinding.Kind;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.model.TableSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @Controller 폼 커맨드의 평면 폼 body를 bindingKind별로 합성한다.
 * <ul>
 *   <li>SCALAR: 기존 스칼라 합성(SampleInputSynthesizer 위임).</li>
 *   <li>NESTED: 중첩 POJO를 점-경로 스칼라 키(prefix.sub=scalar)로 재귀 평면화 — formEncode가 비-스칼라를
 *       드롭하므로 중첩 ObjectNode를 두지 않는다.</li>
 *   <li>REFERENCE: 러너가 백업행에서 산출한 refValues(필드→토큰)를 주입(없으면 skip → 스칼라/skip 폴백).</li>
 * </ul>
 * 중첩 평면화는 커맨드 shape를 평면 BodyField 목록으로 변환한 뒤 SampleInputSynthesizer에 위임해(스칼라 로직
 * 재사용) 결정적 happy 값을 채운다.
 */
public class FormBodySynthesizer {

    private static final int MAX_DEPTH = 4;

    private final Map<String, List<String>> enumConstants;

    public FormBodySynthesizer(Map<String, List<String>> enumConstants) {
        this.enumConstants = enumConstants;
    }

    public SynthesizedInput synthesize(BodyShape commandShape, Map<String, BodyShape> shapesByType,
                                       List<FormFieldBinding> bindings, Map<String, String> refValues,
                                       List<TableSchema> tables,
                                       Map<String, List<FieldConstraint>> fieldConstraints) {
        return synthesize(commandShape, shapesByType, bindings, refValues, tables, fieldConstraints, "");
    }

    public SynthesizedInput synthesize(BodyShape commandShape, Map<String, BodyShape> shapesByType,
                                       List<FormFieldBinding> bindings, Map<String, String> refValues,
                                       List<TableSchema> tables,
                                       Map<String, List<FieldConstraint>> fieldConstraints,
                                       String endpointId) {
        Map<String, Kind> kindByField = bindings.stream()
                .collect(Collectors.toMap(FormFieldBinding::field, FormFieldBinding::kind, (a, b) -> a));

        List<BodyField> flatFields = new ArrayList<>();
        Set<String> referenceFields = new HashSet<>();
        for (BodyField field : commandShape.fields()) {
            switch (kindByField.getOrDefault(field.name(), Kind.SCALAR)) {
                case REFERENCE -> referenceFields.add(field.name());   // refValues로 주입(아래)
                case NESTED -> flattenInto(flatFields, field.name(), field.javaType(),
                        shapesByType, 0, new HashSet<>());
                case SCALAR -> flatFields.add(field);
            }
        }

        SynthesizedInput base = new SampleInputSynthesizer(enumConstants, endpointId)
                .synthesize(new BodyShape(commandShape.javaType(), flatFields), tables, fieldConstraints);
        ObjectNode body = (ObjectNode) base.body();

        for (String refField : referenceFields) {
            String token = refValues.get(refField);
            if (token != null) {
                body.set(refField, TextNode.valueOf(token));
            }
        }
        return new SynthesizedInput(body, base.seeds());
    }

    /** 중첩 POJO를 prefix.sub 점-경로 스칼라 BodyField로 재귀 전개. 빈 POJO/순환/깊이 초과 → prefix 스칼라 폴백. */
    private void flattenInto(List<BodyField> out, String prefix, String typeFqn,
                             Map<String, BodyShape> shapesByType, int depth, Set<String> visited) {
        BodyShape shape = shapesByType.get(typeFqn);
        if (shape == null || shape.fields().isEmpty() || depth >= MAX_DEPTH || !visited.add(typeFqn)) {
            out.add(new BodyField(prefix, typeFqn));   // 스칼라 폴백(SampleInputSynthesizer가 sample 텍스트로 채움)
            return;
        }
        for (BodyField sub : shape.fields()) {
            String key = prefix + "." + sub.name();
            BodyShape subShape = shapesByType.get(sub.javaType());
            if (subShape != null && !subShape.fields().isEmpty()) {
                flattenInto(out, key, sub.javaType(), shapesByType, depth + 1, visited);
            } else {
                out.add(new BodyField(key, sub.javaType()));
            }
        }
        visited.remove(typeFqn);
    }
}
