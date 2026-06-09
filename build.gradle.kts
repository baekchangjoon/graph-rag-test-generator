plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
}

subprojects {
    plugins.withType<JavaPlugin> {
        extensions.configure<JavaPluginExtension> {
            toolchain {
                languageVersion = JavaLanguageVersion.of(17)
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
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
