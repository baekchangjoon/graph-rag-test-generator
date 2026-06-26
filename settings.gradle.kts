rootProject.name = "graph-rag"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // pjacoco-agent (io.pjacoco:pjacoco-agent) — Maven Central 미배포. GitHub Release에서 직접 해소한다
        // (REQ-P012: CI/clean/로컬 어디서나 재현가능, mavenLocal·외부 repo 빌드 불요). io.pjacoco로 scope 한정.
        // 예: io.pjacoco:pjacoco-agent:1.4.0 → .../releases/download/v1.4.0/pjacoco-agent-1.4.0.jar
        ivy {
            name = "pjacoco-github-releases"
            url = uri("https://github.com/baekchangjoon/parallel-per-test-coverage/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }   // ivy.xml/pom 없음 — jar 아티팩트 직접 해소
            content { includeModule("io.pjacoco", "pjacoco-agent") }
        }
    }
}

include(
    "shared-model",
    "testlib",
    "test-state-dashboard",
    "socket-mock-server",
    "graph-rag-builder",
    "test-generator",
    "samples:order-service",
    "samples:gateway-service",
    "samples:error-envelope-service",
    "e2e",
)
