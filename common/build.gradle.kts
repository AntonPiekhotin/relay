plugins {
    kotlin("jvm") version "2.2.21"
    `maven-publish`
}

group = "com.relay"
version = "1.0"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("jakarta.validation:jakarta.validation-api:3.1.1")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["kotlin"])
        }
    }
}
