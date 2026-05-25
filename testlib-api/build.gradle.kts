plugins {
    `java-library`
}

dependencies {
    api(project(":shared-model"))

    testImplementation(libs.bundles.testing.base)
    testRuntimeOnly(libs.bundles.junit.runtime)
}
