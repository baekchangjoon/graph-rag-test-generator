plugins {
    java
}

dependencies {
    testImplementation(project(":testlib"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.postgresql)
}

// run-phase0.sh가 생성된 테스트를 이 디렉터리로 복사한다
sourceSets.test {
    java.srcDir(layout.buildDirectory.dir("generated-tests"))
}

// 생성 테스트는 docker-compose 환경이 떠 있을 때만 의미가 있다
tasks.test {
    onlyIf { System.getenv("APP_BASE_URI") != null }
    outputs.upToDateWhen { false }
}
