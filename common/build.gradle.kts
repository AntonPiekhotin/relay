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

    // Observability slice (com.relay.common.observability) — compileOnly on purpose, and this is
    // load-bearing rather than stylistic. This module publishes `from(components["kotlin"])`,
    // which maps `implementation` dependencies to *runtime* scope in the POM: declared that way,
    // these would land on every consumer's runtime classpath — spring-kafka on user-service,
    // which has no Kafka — and would force a Spring version chosen here rather than the one each
    // service's own Boot BOM resolves. `compileOnly` is not published at all, so the POM gains
    // nothing and forces nothing. Each @AutoConfiguration is @ConditionalOnClass-guarded, so a
    // service missing one of these simply skips that slice.
    //
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
