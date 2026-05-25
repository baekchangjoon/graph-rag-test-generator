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
    testRuntimeOnly(libs.bundles.junit.runtime)
}
