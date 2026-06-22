plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // 빌더(도구 1)가 SUT 기동 완료를 /actuator/health 로 폴링하므로 actuator 필수.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly(libs.postgresql)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit)
}
