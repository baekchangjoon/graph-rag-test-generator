plugins {
    `java-library`
}

dependencies {
    implementation(project(":testlib-api"))

    testImplementation(libs.bundles.testing.base)
    testRuntimeOnly(libs.bundles.junit.runtime)
}
