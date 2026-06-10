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
    implementation(libs.jacoco.core)
    implementation(libs.jacoco.agent)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.simple)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
}

// 통합 테스트(BuilderE2eTest)는 샘플 SUT jar가 필요하다
tasks.test {
    dependsOn(":samples:order-service:bootJar")
    systemProperty("sut.jar",
        project(":samples:order-service").layout.buildDirectory
            .file("libs/order-service.jar").get().asFile.absolutePath)
    systemProperty("sut.src",
        project(":samples:order-service").layout.projectDirectory
            .dir("src/main/java").asFile.absolutePath)
}
