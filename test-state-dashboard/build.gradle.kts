plugins {
    java
    application
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

application {
    mainClass.set("io.graphrag.dashboard.DashboardApplication")
}

dependencies {
    implementation(project(":shared-model"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")

    // Spring Boot BOM이 SLF4J + logback의 일관된 버전을 제공.
    // libs.bundles.testing.base / libs.logback.classic 등은 BOM과 충돌 가능 → 추가하지 않음.

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
