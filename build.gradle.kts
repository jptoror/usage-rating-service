import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    jacoco
}

group = "com.revenium"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    compilerOptions {
        // JSR-305 strict: Spring's @Nullable/@NonNull become Kotlin platform-type guarantees.
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget = JvmTarget.JVM_21
    }
}

// `allOpen` for JPA entities: Kotlin classes are final by default, Hibernate needs to proxy them.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.aspectj)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.logging)

    implementation(libs.liquibase.core)
    implementation(libs.springdoc.openapi.webmvc.ui)

    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test) {
        // We use MockK instead of Mockito throughout.
        exclude(group = "org.mockito")
    }
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
    testImplementation(libs.awaitility)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// ---------------------------------------------------------------------------
// Test tasks
//
// `test`            -> unit tests only. Fast, no Docker. Feeds the coverage gate.
// `integrationTest` -> everything tagged "integration". Needs Docker/Testcontainers.
// `check`           -> runs both, then verifies coverage.
//
// The coverage gate measures UNIT test line coverage, as the brief requires.
// Integration tests are excluded from the gate so that coverage cannot be
// inflated by spinning up a full context and touching code incidentally.
// ---------------------------------------------------------------------------

tasks.test {
    useJUnitPlatform {
        excludeTags("integration")
    }
    finalizedBy(tasks.jacocoTestReport)
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests (Testcontainers). Requires a running Docker daemon."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    useJUnitPlatform {
        includeTags("integration")
    }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    shouldRunAfter(tasks.test)
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// Production code that is excluded from the coverage gate.
// Documented in README.md -- only framework bootstrap and pure wiring.
val coverageExclusions = listOf(
    "com/revenium/usage/UsageRatingApplication*",       // @SpringBootApplication main()
    "com/revenium/usage/shared/config/**",              // @Configuration bean wiring
)

tasks.jacocoTestReport {
    dependsOn(tasks.test, tasks.classes)
    reports {
        xml.required = true
        html.required = true
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { dir ->
            fileTree(dir) { exclude(coverageExclusions) }
        })
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport, tasks.classes)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
    classDirectories.setFrom(
        files(classDirectories.files.map { dir ->
            fileTree(dir) { exclude(coverageExclusions) }
        })
    )
}

tasks.check {
    dependsOn(integrationTest, tasks.jacocoTestCoverageVerification)
}
