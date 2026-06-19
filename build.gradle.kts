plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
    jacoco   // 루트 집계 JacocoReport 태스크에 jacocoClasspath/리포트 경로 기본값 제공
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// 프로젝트 자체 테스트 커버리지 집계(제품 모듈 전체) — docs/05-testing.md "커버리지" 절.
// 집계 대상 = 제품 모듈만. samples(fixture SUT)·e2e(out-of-process 하네스)는 제외.
// 외부 의존성을 해석하는 jacoco-report-aggregation 플러그인은 Spring Boot 모듈의 BOM 버전을
// 루트에서 못 풀어 실패하므로, exec+classes+sources만 읽는 고전적 수동 JacocoReport를 쓴다.
val coverageModules = listOf(
    "shared-model", "testlib", "test-state-dashboard",
    "socket-mock-server", "graph-rag-builder", "test-generator",
)

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    description = "제품 모듈 전체의 자체 테스트 커버리지를 하나로 집계한다."
    val covered = coverageModules.map { project(":$it") }
    covered.forEach { dependsOn(it.tasks.named("test")) }
    reports {
        xml.required = true   // PR 코멘트 파싱용
        html.required = true
        csv.required = false
    }
    // sourceSet 접근은 대상 프로젝트 평가 이후라야 안전 → provider로 실행 시점에 지연 해석.
    fun mains() = covered.map { it.extensions.getByType<SourceSetContainer>()["main"] }
    sourceDirectories.setFrom(provider { mains().flatMap { it.allSource.srcDirs } })
    classDirectories.setFrom(provider { mains().map { it.output.classesDirs } })
    executionData.setFrom(
        covered.map { p -> p.fileTree(p.layout.buildDirectory) { include("jacoco/test.exec") } }
    )
}

// 릴리스 버전: git 태그가 주입하는 RELEASE_VERSION(예: v1.2.3 → 1.2.3). 미설정 시 스냅샷.
// 배포 산출물 3종에만 적용한다 — 샘플/서비스 모듈에 적용하면 bootJar 이름이 바뀌어
// (order-service-<v>.jar) run-e2e.sh·통합테스트가 참조하는 order-service.jar 등이 깨진다.
val releaseVersion = providers.environmentVariable("RELEASE_VERSION").orElse("0.0.0-SNAPSHOT").get()
subprojects {
    if (name in setOf("graph-rag-builder", "test-generator", "testlib")) {
        version = releaseVersion
    }
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(17)
            }
        }
        // 자체 테스트 커버리지: 각 모듈의 test 실행 시 jacoco exec를 남겨 루트 집계가 모은다.
        apply(plugin = "jacoco")
        extensions.configure<JacocoPluginExtension> {
            toolVersion = rootProject.libs.versions.jacoco.get()
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform {
                // CI 샤딩용 JUnit5 태그 필터: 콤마 구분 멀티 태그 지원
                // (예: -PexcludeTags=integration,docker / -PincludeTags=docker).
                // JUnit5 태그식엔 콤마 연산자가 없으므로 split 후 vararg로 넘긴다.
                fun String.toTags() =
                    split(",").map(String::trim).filter(String::isNotEmpty).toTypedArray()
                (providers.gradleProperty("includeTags").orNull)
                    ?.takeIf { it.isNotBlank() }?.let { includeTags(*it.toTags()) }
                (providers.gradleProperty("excludeTags").orNull)
                    ?.takeIf { it.isNotBlank() }?.let { excludeTags(*it.toTags()) }
            }
            // Docker Engine 29+는 구버전 API(<1.40)를 거부. docker-java가 협상 없이
            // 1.32를 쓰는 문제의 우회 (docs/decisions/sut-analysis-environment.md)
            systemProperty("api.version", "1.44")
            testLogging {
                events("failed", "skipped")
                showStackTraces = true
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
        dependencies {
            "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        }
    }
}
