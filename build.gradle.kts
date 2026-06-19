plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
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
