plugins {
    `java-library`
    application
}

application {
    mainClass = "io.graphrag.generator.cli.GeneratorCli"
}

dependencies {
    implementation(project(":shared-model"))
    implementation(libs.mustache)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}
