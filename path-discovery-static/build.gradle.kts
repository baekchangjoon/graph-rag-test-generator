plugins {
    java
    application
}

application {
    mainClass.set("io.graphrag.discovery.PathDiscoveryStatic")
}

dependencies {
    implementation(project(":shared-model"))
    // JavaParser — Apache 2.0, no Spring runtime dependency.
    implementation("com.github.javaparser:javaparser-core:3.26.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to "io.graphrag.discovery.PathDiscoveryStatic",
            "Implementation-Title" to "graph-rag path-discovery-static",
            "Implementation-Version" to project.version,
        )
    }
}
