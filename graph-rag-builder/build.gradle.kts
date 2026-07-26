plugins {
    `java-library`
    application
}

application {
    mainClass = "io.graphrag.builder.cli.BuilderCli"
    // attach 모드: 호스트의 OTLP/외부-HTTP capture 서버가 IPv4 0.0.0.0에 바인드되어야 컨테이너가
    // host.docker.internal(Docker Desktop host-gateway = IPv4 192.168.65.254)로 도달한다.
    // dual-stack JVM은 wildcard 바인드를 IPv6-only([::])로 열어 컨테이너의 IPv4 연결이 refused되므로
    // 빌더 JVM을 IPv4 스택으로 고정한다. (SUT 컨테이너 측 -Djava.net.preferIPv4Stack 와 대칭.)
    applicationDefaultJvmArgs = listOf("-Djava.net.preferIPv4Stack=true")
}

// OTEL javaagent jar를 리소스로 번들 (분석 환경에서 SUT에 부착, 런타임 다운로드 없음)
val otelAgent: Configuration by configurations.creating
// pjacoco agent jar를 리소스로 번들 (per-trace 커버리지 수집; io.pjacoco:pjacoco-agent mavenLocal)
val pjacocoAgent: Configuration by configurations.creating

dependencies {
    implementation(project(":shared-model"))
    implementation(project(":testlib"))   // 최소 STOMP 클라이언트 재사용 (캡처 경로 = 테스트 경로)
    implementation(libs.spoon)
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.analysis)
    implementation(libs.z3.turnkey)
    implementation(libs.anthropic.java)
    implementation(platform(libs.aws.sdk.bom))   // anthropic-java-bedrock transitive AWS SDK 버전 공급
    implementation(libs.anthropic.java.bedrock)
    implementation(libs.testcontainers.postgresql)
    implementation(libs.testcontainers.mysql)
    implementation(libs.testcontainers.mariadb)
    implementation(libs.testcontainers.kafka)
    implementation(libs.kafka.clients)
    implementation(libs.postgresql)
    implementation(libs.mysql.connector.j)
    implementation(libs.jacoco.core)
    implementation(libs.jacoco.agent)
    implementation(libs.wiremock)
    implementation(libs.instancio.core)
    implementation(libs.slf4j.api)
    implementation(libs.jackson.dataformat.yaml)
    implementation(libs.opentelemetry.proto)
    implementation(libs.jsqlparser)
    runtimeOnly(libs.logback.classic)
    otelAgent(libs.otel.javaagent)
    pjacocoAgent(libs.pjacoco.agent)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.testcontainers.junit)   // @Testcontainers/@Container 확장
    testImplementation(libs.h2)                     // in-memory JDBC for unit tests
    testImplementation(project(":test-generator"))  // EgressStubBodyFidelity* E2E: Generator 실행
}

tasks.processResources {
    from(otelAgent) {
        rename { "otel-javaagent.jar" }
        into("agents")
    }
    from(pjacocoAgent) {
        rename { "pjacoco-agent.jar" }
        into("agents")
    }
}

// graph-diff.sh가 사용하는 testRuntimeClasspath 출력 태스크 (P1-5 gate)
tasks.register("printTestRuntimeClasspath") {
    doLast {
        print(configurations.testRuntimeClasspath.get().asPath)
    }
}

// 통합 테스트는 샘플 SUT jar + 외부 스텁이 필요하다
tasks.test {
    dependsOn(":samples:order-service:bootJar")
    dependsOn(":samples:error-envelope-service:bootJar")
    systemProperty("sut.jar",
        project(":samples:order-service").layout.buildDirectory
            .file("libs/order-service.jar").get().asFile.absolutePath)
    systemProperty("sut.src",
        project(":samples:order-service").layout.projectDirectory
            .dir("src/main/java").asFile.absolutePath)
    systemProperty("external.stubs",
        rootProject.layout.projectDirectory.dir("e2e/external-stubs").asFile.absolutePath)
    // PoC: pjacoco.agent.jar 경로를 테스트 JVM으로 전달 (-Dpjacoco.agent.jar=<path> 로 지정)
    val pjacocoAgentJar: String? = System.getProperty("pjacoco.agent.jar")
    if (pjacocoAgentJar != null) systemProperty("pjacoco.agent.jar", pjacocoAgentJar)

    // Sleuth egress E2E: -Dsut.egress.sleuth=true 로 활성화 (Docker + MySQL Testcontainers 필요)
    val sleuthEgressEnabled: String? = System.getProperty("sut.egress.sleuth")
    if (sleuthEgressEnabled != null) systemProperty("sut.egress.sleuth", sleuthEgressEnabled)
    val orderWebSrc: String? = System.getProperty("order.web.src")
    if (orderWebSrc != null) systemProperty("order.web.src", orderWebSrc)
}
