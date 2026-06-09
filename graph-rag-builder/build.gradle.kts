plugins {
    `java-library`
    application
}

application {
    mainClass = "io.graphrag.builder.cli.BuilderCli"
}

dependencies {
    implementation(project(":shared-model"))
    implementation(libs.spoon)
    implementation(libs.testcontainers.postgresql)
    implementation(libs.postgresql)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}
