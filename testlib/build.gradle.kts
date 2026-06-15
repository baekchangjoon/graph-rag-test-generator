plugins {
    `java-library`
}

dependencies {
    api(project(":shared-model"))
    api(libs.restassured)
    api(libs.kafka.clients)   // 생성 Kafka 테스트의 이벤트 발행
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.slf4j.simple)
}
