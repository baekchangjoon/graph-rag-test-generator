package io.graphrag.builder.cli;

import java.nio.file.Path;

/** 테스트 전용 BuildConfig 헬퍼 — 필수 필드만 채우고 나머지는 기본값. */
class TestConfigs {

    static BuildConfig minimal(Path src, Path out, boolean noInc) {
        return new BuildConfig(src, src.resolveSibling("resources"), null, out,
                "sut", "unknown", null, 0, null, null, null, null, null, null,
                false, false, null, null, null, null, null, noInc);
    }
}
