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

    // 생성된 RestAssured/JUnit 테스트 코드의 javac 컴파일 검증에 필요
    testImplementation(libs.restassured)
    testImplementation(libs.hamcrest)
}
