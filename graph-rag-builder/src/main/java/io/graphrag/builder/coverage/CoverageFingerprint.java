package io.graphrag.builder.coverage;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 요청 1회의 JaCoCo probe 벡터를 SUT 자체 클래스로 한정해 결정적 지문으로 만든다.
 * count 기반 BranchRef와 달리 probe 단위라 true/false arm을 구분 → 같은 라인의 다른 arm을
 * 연 입력이 distinct path로 보존된다. 프레임워크/JDK 클래스는 제외해 노이즈·과보존 방지.
 */
public final class CoverageFingerprint {

    private CoverageFingerprint() {
    }

    public static String of(ExecutionDataStore delta, Set<String> appClasses) {
        List<String> parts = new ArrayList<>();
        for (ExecutionData ed : delta.getContents()) {
            if (!appClasses.contains(ed.getName())) {
                continue;
            }
            boolean[] probes = ed.getProbes();
            StringBuilder idx = new StringBuilder(ed.getName()).append(':');
            for (int i = 0; i < probes.length; i++) {
                if (probes[i]) {
                    idx.append(i).append(',');
                }
            }
            parts.add(idx.toString());
        }
        parts.sort(null);   // 결정적 (클래스 순서 무관)
        // FNV-1a 64bit (지문을 짧게)
        long h = 0xcbf29ce484222325L;
        for (String p : parts) {
            for (int i = 0; i < p.length(); i++) {
                h ^= p.charAt(i);
                h *= 0x100000001b3L;
            }
            h ^= '|';
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h);
    }
}
