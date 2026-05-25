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
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.bundles.testing.base)
    testRuntimeOnly(libs.bundles.junit.runtime)
}
