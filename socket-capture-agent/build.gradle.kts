plugins {
    java
}

dependencies {
    implementation(libs.bytebuddy)
    implementation(libs.bytebuddy.agent)

    testImplementation(libs.bundles.testing.base)
    testRuntimeOnly(libs.bundles.junit.runtime)
}

// Premain-Class manifest 설정 — agent JAR의 핵심
tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "io.graphrag.agent.AgentMain",
            "Agent-Class" to "io.graphrag.agent.AgentMain",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true"
        )
    }
}
