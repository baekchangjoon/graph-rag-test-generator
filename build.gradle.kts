plugins {
    base
}

allprojects {
    group = "io.graphrag"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    // 모든 Java 모듈에 공통 적용
    plugins.withId("java") {
        apply(plugin = "jacoco")

        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(17))
            }
            withSourcesJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.encoding = "UTF-8"
            options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = false
            }
            // Test 끝나면 jacoco 리포트 자동 생성
            finalizedBy(tasks.named("jacocoTestReport"))
        }

        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.named("test"))
            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }
    }
}

// 모든 모듈의 coverage 통합 리포트
val codeCoverageReport by tasks.registering(JacocoReport::class) {
    group = "verification"
    description = "Aggregates coverage from all subprojects."

    subprojects.forEach { sub ->
        sub.plugins.withId("jacoco") {
            executionData(
                fileTree(sub.layout.buildDirectory).include("/jacoco/test.exec")
            )
            sourceSets(sub.the<SourceSetContainer>()["main"])
            dependsOn(sub.tasks.withType<Test>())
        }
    }

    reports {
        xml.required.set(true)
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/aggregate"))
    }
}
