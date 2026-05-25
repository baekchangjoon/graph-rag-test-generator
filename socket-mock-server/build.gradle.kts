plugins {
    java
    application
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

application {
    mainClass.set("io.graphrag.socketmock.SocketMockApplication")
}

dependencies {
    implementation(project(":shared-model"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.netty.all)

    // Spring Boot BOM이 SLF4J + logback 일관 제공.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
