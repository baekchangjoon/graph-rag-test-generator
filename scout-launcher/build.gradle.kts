plugins {
    java
    application
}

application {
    mainClass.set("io.graphrag.scout.ScoutLauncher")
}

dependencies {
    implementation(project(":shared-model"))
    // YAML config parsing
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.18.2")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.26.3")
    testImplementation("org.mockito:mockito-core:5.14.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.14.0")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "io.graphrag.scout.ScoutLauncher",
            "Implementation-Title" to "graph-rag scout-launcher",
            "Implementation-Version" to project.version,
        )
    }
}
