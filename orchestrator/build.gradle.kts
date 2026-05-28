plugins {
    java
    application
}

application {
    mainClass.set("io.graphrag.orchestrator.Orchestrator")
}

dependencies {
    implementation(project(":shared-model"))
    implementation(project(":path-discovery-static"))
    implementation(project(":scout-step-translator"))
    implementation(project(":scout-launcher"))
    implementation(project(":coverage-feedback"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

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
