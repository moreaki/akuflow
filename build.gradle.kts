plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
}

group = "com.convertic.akuflow"
version = "0.0.1-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)

    implementation(libs.jackson.module.kotlin)

    implementation(libs.temporal.spring.boot.starter)
    implementation(libs.camunda.bpmn.model)
    implementation(libs.groovy.all)

    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.stdlib.jdk8)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
