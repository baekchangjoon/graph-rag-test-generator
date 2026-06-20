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
    // 생성 소스 컴파일 검증(GeneratorKafkaServerFieldsTest): 생성된 테스트 코드가 참조하는
    // 타입(TestScope, JSONAssert, ConsumerRecord, Hamcrest)을 컴파일타임에 리졸브하기 위한 의존.
    testImplementation(project(":testlib"))
}
