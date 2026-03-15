plugins {
    kotlin("jvm") version "2.3.0"
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

application { mainClass.set("org.example.MainKt") }

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.openai:openai-java:4.28.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}