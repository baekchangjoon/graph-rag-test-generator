plugins {
    java
}

// compose의 SUT 컨테이너에 부착할 OTEL javaagent (docs/06)
val otelAgent: Configuration by configurations.creating

dependencies {
    testImplementation(project(":testlib"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
    testRuntimeOnly(libs.mysql.connector.j)   // MSA의 MySQL 기반 서비스(auth-user 등) 대상
    otelAgent(libs.otel.javaagent)
}

val copyOtelAgent by tasks.registering(Copy::class) {
    from(otelAgent) { rename { "otel-javaagent.jar" } }
    into(layout.projectDirectory.dir("agents"))
}

// run-e2e.sh가 생성된 테스트를 이 디렉터리로 복사한다
sourceSets.test {
    java.srcDir(layout.buildDirectory.dir("generated-tests"))
}

// 생성 테스트는 docker-compose 환경이 떠 있을 때만 의미가 있다
tasks.test {
    onlyIf { System.getenv("APP_BASE_URI") != null }
    outputs.upToDateWhen { false }
}
