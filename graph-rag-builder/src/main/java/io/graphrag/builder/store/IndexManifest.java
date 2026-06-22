package io.graphrag.builder.store;

import java.util.Map;

/** 캐시 매니페스트: 스키마 버전 + 소스 파일 내용 해시. */
public record IndexManifest(int schemaVersion, Map<String, FileEntry> files) {

    /** root = "sutSrc" | "sutResources", hash = 파일 내용 SHA-256. */
    public record FileEntry(String root, String hash) {
    }
}
