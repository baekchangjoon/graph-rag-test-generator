package io.graphrag.builder.store;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 모듈/패키지 단위 파티셔닝 규칙 (roadmap 6.2). 파티션 키 = 핸들러 클래스의 패키지.
 * 매핑 불가능한 변경(리소스/XML 등)은 전체 파티션을 더티로 본다 (보수적 안전 규칙).
 */
public final class GraphPartitioner {

    private GraphPartitioner() {
    }

    /** FQCN → 파티션 키 (패키지명, default package면 클래스명 그대로). */
    public static String partitionOf(String handlerClass) {
        int lastDot = handlerClass.lastIndexOf('.');
        return lastDot < 0 ? handlerClass : handlerClass.substring(0, lastDot);
    }

    /** 변경 파일 목록 → 더티 파티션 집합. */
    public static Set<String> dirtyPartitions(Collection<String> changedFiles,
                                              Set<String> knownPartitions) {
        Set<String> dirty = new LinkedHashSet<>();
        for (String file : changedFiles) {
            String normalized = file.replace('\\', '/');
            String matched = null;
            for (String partition : knownPartitions) {
                if (normalized.contains(partition.replace('.', '/') + "/")) {
                    matched = partition;
                    break;
                }
            }
            if (matched == null) {
                return knownPartitions;
            }
            dirty.add(matched);
        }
        return dirty;
    }
}
