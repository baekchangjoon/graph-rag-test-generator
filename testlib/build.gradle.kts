plugins {
    `java-library`
}

dependencies {
    api(project(":shared-model"))
    api(libs.restassured)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.slf4j.simple)
}
