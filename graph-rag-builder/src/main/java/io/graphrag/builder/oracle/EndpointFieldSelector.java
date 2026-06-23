package io.graphrag.builder.oracle;

import io.graphrag.builder.index.BodyShape;
import io.graphrag.builder.index.ValidationConstraintExtractor.FieldConstraint;
import io.graphrag.builder.index.ValidationConstraintExtractor.Kind;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * LLM에 보낼 가치가 있는 엄격 검증 String 필드만 선별. @Pattern/@Email + 도메인 코드 키워드.
 * 순수 숫자·평범한 String은 제외(싼 오라클이 이미 커버). String 채널만 보므로 Java enum 타입
 * 필드(비-String)는 자동 제외된다.
 */
public final class EndpointFieldSelector {
    private static final Set<String> DOMAIN_KEYWORDS =
            Set.of("status", "type", "code", "tier", "grade", "category", "level");

    private EndpointFieldSelector() {
    }

    public record Selected(List<BodyShape.BodyField> fields,
                           Map<String, String> patternByField, Set<String> emailFields) {
    }

    public static Selected select(List<BodyShape.BodyField> fields,
                                  Map<String, List<FieldConstraint>> constraints) {
        List<BodyShape.BodyField> chosen = new ArrayList<>();
        Map<String, String> patterns = new TreeMap<>();
        Set<String> emails = new TreeSet<>();
        for (BodyShape.BodyField f : fields) {
            if (!f.javaType().equals("java.lang.String")) {
                continue;   // String 채널만 — enum 타입(비-String) 자동 제외
            }
            boolean strict = false;
            for (FieldConstraint c : constraints.getOrDefault(f.name(), List.of())) {
                if (c.kind() == Kind.PATTERN && c.strArg() != null) {
                    patterns.put(f.name(), c.strArg());
                    strict = true;
                } else if (c.kind() == Kind.EMAIL) {
                    emails.add(f.name());
                    strict = true;
                }
            }
            if (!strict && DOMAIN_KEYWORDS.contains(f.name().toLowerCase())) {
                strict = true;
            }
            if (strict) {
                chosen.add(f);
            }
        }
        return new Selected(chosen, patterns, emails);
    }
}
