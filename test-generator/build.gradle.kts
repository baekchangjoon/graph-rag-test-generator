plugins {
    java
    application
}

application {
    mainClass.set("io.graphrag.generator.GeneratorApplication")
}

dependencies {
    implementation(project(":shared-model"))
    implementation(libs.bundles.jackson)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(libs.bundles.testing.base)
    testRuntimeOnly(libs.bundles.junit.runtime)
}
