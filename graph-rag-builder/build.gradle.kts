plugins {
    java
    application
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

application {
    mainClass.set("io.graphrag.builder.BuilderApplication")
}

// 외부 프로젝트(예: petclinic) 가 testImplementation 로 사용할 수 있도록 plain jar 도 publishing
// (Spring Boot 플러그인이 bootJar 활성 시 default jar 비활성. enabling.)
tasks.named<Jar>("jar") { enabled = true; archiveClassifier.set("") }
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveClassifier.set("boot")
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

    // jdbc-intercept-agent contract — JdbcAgentBaggageBridge 어댑터가 사용.
    // agent-core (javaagent jar) 는 SUT JVM 에 -javaagent 로 attach 되며 graph-rag 의 compile 의존
    // 아님 — runtime 만 필요.
    implementation("io.jdbcintercept:agent-api:1.0.0-SNAPSHOT")
    // OTEL Baggage API (선택) — Servlet handler thread propagation 우회용.
    // 없어도 compile/실행 모두 정상. 있으면 어댑터가 baggage 에서 path-id fallback 조회.
    compileOnly("io.opentelemetry:opentelemetry-api:1.49.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.opentelemetry:opentelemetry-api:1.49.0")
    testImplementation("io.opentelemetry:opentelemetry-context:1.49.0")
    testImplementation(libs.h2)
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.neo4j)
}
