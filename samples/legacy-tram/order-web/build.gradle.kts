plugins {
    java
    id("org.springframework.boot") version "2.7.18"
    id("io.spring.dependency-management") version "1.0.15.RELEASE"
}
java { toolchain { languageVersion.set(JavaLanguageVersion.of(8)) } }
repositories { mavenCentral() }
extra["springCloudVersion"] = "2021.0.8"
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.cloud:spring-cloud-starter-sleuth")
    runtimeOnly("mysql:mysql-connector-java:8.0.28")  // Eventuate BOM 없이 버전 명시 (Boot 2.7 BOM은 com.mysql:mysql-connector-j만 관리)
}
dependencyManagement {
    imports { mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}") }
}
