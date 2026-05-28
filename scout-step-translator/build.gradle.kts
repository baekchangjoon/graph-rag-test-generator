plugins {
    java
    application
}

application {
    mainClass.set("io.graphrag.translator.ScoutStepTranslator")
}

dependencies {
    implementation(project(":shared-model"))
    implementation(project(":scout-launcher"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "io.graphrag.translator.ScoutStepTranslator",
            "Implementation-Title" to "graph-rag scout-step-translator",
            "Implementation-Version" to project.version,
        )
    }
}
