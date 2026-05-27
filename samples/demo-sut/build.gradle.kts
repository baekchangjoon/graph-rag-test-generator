plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

// jdbc-intercept-agent core jar — resolved as a runtime-only configuration so we can
// pass its file path to the test JVM via -javaagent.
val jdbcAgent: Configuration by configurations.creating

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation(libs.netty.all)
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
    testImplementation(project(":socket-mock-server"))
    testImplementation(project(":socket-capture-agent"))

    // Agent attach E2E — agent-api on compile, agent-core via custom configuration for -javaagent
    testImplementation("io.jdbcintercept:agent-api:1.0.0-SNAPSHOT")
    jdbcAgent("io.jdbcintercept:agent-core:1.0.0-SNAPSHOT")
}

tasks.test {
    val agentJar = jdbcAgent.singleFile.absolutePath
    jvmArgs("-javaagent:$agentJar")
    systemProperty("graphrag.jdbcAgentJar", agentJar)
}
