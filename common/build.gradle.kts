plugins {
    kotlin("jvm") version "2.3.21"
    `maven-publish`
}

group = "com.relay"
version = "1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("jakarta.validation:jakarta.validation-api:3.1.1")

    // Deliberately NOT using io.spring.dependency-management: that plugin injects a
    // <dependencyManagement> block into the published POM, exporting the whole Boot BOM into
    // every consumer's dependency resolution. A platform() on compileOnly has no such effect.
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    compileOnly("org.springframework.boot:spring-boot")
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework.kafka:spring-kafka")
    compileOnly("org.apache.kafka:kafka-clients")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.slf4j:slf4j-api")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["kotlin"])
        }
    }
}
