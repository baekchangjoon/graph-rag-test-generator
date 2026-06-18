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
    implementation("io.eventuate.tram.core:eventuate-tram-spring-jdbc-kafka:0.35.0.RELEASE")
    implementation("io.eventuate.tram.core:eventuate-tram-spring-events:0.35.0.RELEASE")
    // NOTE: eventuate-tram-spring-cloud-sleuth-integration (io.eventuate.tram.core) was discontinued
    // after 0.29.0.RELEASE. The current Sleuth integration uses a separate group.
    implementation("io.eventuate.tram.springcloudsleuth:eventuate-tram-spring-cloud-sleuth-tram-starter:0.5.0.RELEASE")
    runtimeOnly("mysql:mysql-connector-java")   // Boot 2.7 BOM manages this coordinate
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}
