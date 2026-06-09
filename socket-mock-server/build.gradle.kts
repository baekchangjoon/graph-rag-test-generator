plugins {
    java
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.netty.handler)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
