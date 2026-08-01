plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.relay"
version = "0.0.1-SNAPSHOT"
description = "auth"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}

extra["springCloudVersion"] = "2025.1.2"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-security")
    // POST /api/v1/auth/password is authenticated: the account whose credential is being replaced
    // comes from a validated token, never from the request body.
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.keycloak:keycloak-admin-client:26.0.8")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    // No spring-boot-starter-data-jpa-test here: auth owns no database, and having it on the test
    // classpath dragged in JPA auto-configuration, which failed the context for want of a JDBC driver.
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")

    implementation("org.springframework.kafka:spring-kafka")

    implementation("com.relay:common:1.0")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
