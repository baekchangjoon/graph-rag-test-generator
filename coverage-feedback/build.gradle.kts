plugins {
    java
    application
}

application {
    mainClass.set("io.graphrag.feedback.CoverageFeedback")
}

dependencies {
    implementation(project(":shared-model"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "io.graphrag.feedback.CoverageFeedback",
            "Implementation-Title" to "graph-rag coverage-feedback",
            "Implementation-Version" to project.version,
        )
    }
}
