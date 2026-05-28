plugins {
    java
    application
}

application {
    mainClass.set("io.graphrag.orchestrator.Orchestrator")
}

configurations.all {
    // The Spring Boot BOM (pulled transitively through :graph-rag-builder) upgrades
    // junit-platform-engine to 1.12.2 but Gradle's embedded launcher is 1.10.x,
    // causing "OutputDirectoryProvider not available". Pin the whole JUnit platform
    // back to the version declared in testImplementation so engine and launcher match.
    resolutionStrategy.eachDependency {
        if (requested.group == "org.junit.platform") {
            useVersion("1.10.2")
            because("align junit-platform-* with junit-jupiter:5.10.2 to avoid engine/launcher mismatch")
        }
        if (requested.group == "org.junit.jupiter") {
            useVersion("5.10.2")
            because("keep junit-jupiter aligned at declared version")
        }
        if (requested.group == "org.junit" && requested.name == "junit-bom") {
            useVersion("5.10.2")
            because("keep junit-bom aligned at declared version")
        }
    }
}

dependencies {
    // Spring Boot BOM — provides versions for transitive Spring deps pulled in by
    // :graph-rag-builder. Using runtimeOnly so it does NOT propagate to our consumers.
    runtimeOnly(platform("org.springframework.boot:spring-boot-dependencies:3.5.0"))
    testRuntimeOnly(platform("org.springframework.boot:spring-boot-dependencies:3.5.0"))

    implementation(project(":shared-model"))
    implementation(project(":graph-rag-builder"))
    implementation(project(":scout-step-translator"))
    implementation(project(":scout-launcher"))
    implementation(project(":coverage-feedback"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.14.0")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "io.graphrag.orchestrator.Orchestrator",
            "Implementation-Title" to "graph-rag orchestrator",
            "Implementation-Version" to project.version,
        )
    }
}
