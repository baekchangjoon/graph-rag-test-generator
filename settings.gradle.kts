rootProject.name = "graph-rag"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
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
    "e2e",
)
