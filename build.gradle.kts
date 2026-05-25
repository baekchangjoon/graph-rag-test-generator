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
        }
    }
}
