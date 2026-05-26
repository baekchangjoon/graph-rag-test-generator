plugins {
    java
    application
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

application {
    mainClass.set("io.graphrag.builder.BuilderApplication")
}

dependencies {
    implementation(project(":shared-model"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.datasource.proxy)
    implementation(libs.mybatis)
    implementation(libs.wiremock)
    implementation(libs.neo4j.driver)
    implementation(libs.coverage.jacoco.core)
    implementation("org.springframework:spring-messaging")

    // 분석 환경에서 JPA SUT를 부팅하기 위한 deps.
    // 운영 코드에는 noop이지만 테스트/분석 harness에서 사용됨.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.neo4j)
}
