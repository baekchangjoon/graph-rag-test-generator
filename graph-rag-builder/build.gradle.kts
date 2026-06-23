plugins {
    `java-library`
    application
}

application {
    mainClass = "io.graphrag.builder.cli.BuilderCli"
}

// OTEL javaagent jar를 리소스로 번들 (분석 환경에서 SUT에 부착, 런타임 다운로드 없음)
val otelAgent: Configuration by configurations.creating

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
    runtimeOnly(libs.slf4j.simple)
    otelAgent(libs.otel.javaagent)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.testcontainers.junit)   // @Testcontainers/@Container 확장
    testImplementation(libs.h2)                     // in-memory JDBC for unit tests
}

tasks.processResources {
    from(otelAgent) {
        rename { "otel-javaagent.jar" }
        into("agents")
    }
}

// 통합 테스트는 샘플 SUT jar + 외부 스텁이 필요하다
tasks.test {
    dependsOn(":samples:order-service:bootJar")
    systemProperty("sut.jar",
        project(":samples:order-service").layout.buildDirectory
            .file("libs/order-service.jar").get().asFile.absolutePath)
    systemProperty("sut.src",
        project(":samples:order-service").layout.projectDirectory
            .dir("src/main/java").asFile.absolutePath)
    systemProperty("external.stubs",
        rootProject.layout.projectDirectory.dir("e2e/external-stubs").asFile.absolutePath)
}
