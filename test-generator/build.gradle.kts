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

    // self-check (run-after-compile)에 JUnit5 launcher 사용
    implementation("org.junit.platform:junit-platform-launcher:1.11.0")
    implementation("org.junit.jupiter:junit-jupiter-engine:5.11.0")
    implementation("org.junit.jupiter:junit-jupiter-api:5.11.0")

    testImplementation(libs.bundles.testing.base)
    testRuntimeOnly(libs.bundles.junit.runtime)

    // 생성된 RestAssured/JUnit 테스트 코드의 javac 컴파일 검증에 필요
    testImplementation(libs.restassured)
    testImplementation(libs.hamcrest)
    testImplementation(libs.wiremock)
    testImplementation("org.springframework:spring-messaging:6.2.0")
    testImplementation("org.springframework:spring-websocket:6.2.0")
}
