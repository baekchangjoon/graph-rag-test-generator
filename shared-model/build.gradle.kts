plugins {
    `java-library`
}

dependencies {
    api(libs.bundles.jackson)
    api(libs.ulid.creator)

    testImplementation(libs.bundles.testing.base)
    testRuntimeOnly(libs.bundles.junit.runtime)
}
