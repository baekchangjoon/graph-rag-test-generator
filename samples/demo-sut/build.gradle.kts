plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    runtimeOnly(libs.postgres.jdbc)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.h2)
    testImplementation(libs.datasource.proxy)
    testImplementation(libs.restassured)
    testImplementation(libs.wiremock)
    // E2E 테스트에서 graph-rag-builder + test-generator + shared-model 사용
    testImplementation(project(":shared-model"))
    testImplementation(project(":graph-rag-builder"))
    testImplementation(project(":test-generator"))
}
