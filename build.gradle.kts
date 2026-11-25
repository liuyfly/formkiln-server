import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.2.0"
}

group = "io.formkiln"
version = "0.0.1-SNAPSHOT"
description = "Formkiln server: form definitions, drafts, submissions and server-side validation."

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("com.networknt:json-schema-validator:3.0.9")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // S3-compatible object storage (AWS S3, Cloudflare R2, MinIO, ...).
    implementation(platform("software.amazon.awssdk:bom:2.59.9"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")
    // Argon2id password hashing (Spring Security's Argon2PasswordEncoder).
    implementation("org.bouncycastle:bcprov-jdk18on:1.87")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Downloads the pinned @formkiln/spec tarball from npm and verifies its integrity.
val specVersion = providers.gradleProperty("formkilnSpecVersion").get()
val specIntegrity = providers.gradleProperty("formkilnSpecIntegrity").get()
val specTarball = layout.buildDirectory.file("formkiln-spec/spec-$specVersion.tgz")
val specDir = layout.buildDirectory.dir("formkiln-spec/$specVersion")

val downloadSpec by tasks.registering {
    description = "Downloads and verifies @formkiln/spec@$specVersion."
    inputs.property("version", specVersion)
    inputs.property("integrity", specIntegrity)
    outputs.file(specTarball)
    // Copy into locals so the action does not capture the build script (configuration cache).
    val version = specVersion
    val integrity = specIntegrity
    val target = specTarball
    doLast {
        val url = URI("https://registry.npmjs.org/@formkiln/spec/-/spec-$version.tgz").toURL()
        val bytes = url.openStream().use { it.readAllBytes() }
        val digest = MessageDigest.getInstance("SHA-512").digest(bytes)
        val actual = "sha512-" + Base64.getEncoder().encodeToString(digest)
        check(actual == integrity) { "Integrity mismatch for @formkiln/spec@$version: $actual" }
        target.get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }
}

val extractSpec by tasks.registering(Sync::class) {
    description = "Extracts @formkiln/spec@$specVersion for the conformance tests."
    dependsOn(downloadSpec)
    from(tarTree(resources.gzip(specTarball))) {
        eachFile { path = path.removePrefix("package/") }
        includeEmptyDirs = false
    }
    into(specDir)
}

// -PformkilnSpecDir=../formkiln/packages/spec builds and tests against an unpublished local spec.
val localSpecDir = providers.gradleProperty("formkilnSpecDir").orNull?.let { file(it) }

// The form JSON Schema ships inside the application for structural validation at publish time.
tasks.processResources {
    if (localSpecDir == null) {
        dependsOn(extractSpec)
    }
    from(localSpecDir ?: specDir) {
        include("schema/form-v1.schema.json")
        into("formkiln-spec")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    if (localSpecDir != null) {
        inputs.dir(localSpecDir)
        systemProperty("formkiln.spec.dir", localSpecDir.absolutePath)
    } else {
        dependsOn(extractSpec)
        inputs.dir(specDir)
        systemProperty("formkiln.spec.dir", specDir.get().asFile.absolutePath)
    }
}
