rootProject.name = "graph-rag"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

// Phase 0 modules
include(
    ":shared-model",
    ":testlib-api",
    ":testlib-adapter-noop",
    ":test-state-dashboard",
    ":socket-mock-server",
    ":graph-rag-builder",
    ":test-generator",
    ":socket-capture-agent",
)

// Phase 0 PoC SUT (separate samples directory)
include(":samples:demo-sut")
project(":samples:demo-sut").projectDir = file("samples/demo-sut")
