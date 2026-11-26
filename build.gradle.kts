import java.net.URI
import java.security.MessageDigest
import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.2.0"
}

group = "io.formkiln"
// Release builds pass -PformkilnVersion=0.1.0 (the image build takes it from the git tag).
version = providers.gradleProperty("formkilnVersion").getOrElse("0.1.0-SNAPSHOT")
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
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
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

// The form specification (JSON Schema, conformance cases, examples) comes from packages/spec of
// liuyfly/formkiln at a pinned commit, verified by a SHA-256 digest of the extracted files. No npm
// release is needed to follow a spec change: see CONTRIBUTING.md.
val specCommit = providers.gradleProperty("formkilnSpecCommit").get()
val specSha256 = providers.gradleProperty("formkilnSpecSha256").get()
val specTarball = layout.buildDirectory.file("formkiln-spec/formkiln-$specCommit.tar.gz")
val specDir = layout.buildDirectory.dir("formkiln-spec/$specCommit")

val downloadSpec by tasks.registering {
    description = "Downloads liuyfly/formkiln at commit $specCommit."
    inputs.property("commit", specCommit)
    outputs.file(specTarball)
    // Copy into locals so the action does not capture the build script (configuration cache).
    val commit = specCommit
    val target = specTarball
    doLast {
        check(Regex("[0-9a-f]{40}").matches(commit)) { "formkilnSpecCommit must be a full 40-character commit SHA" }
        val url = URI("https://codeload.github.com/liuyfly/formkiln/tar.gz/$commit").toURL()
        val bytes = url.openStream().use { it.readAllBytes() }
        target.get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(bytes)
        }
    }
}

val extractSpec by tasks.registering(Sync::class) {
    description = "Extracts and verifies packages/spec of liuyfly/formkiln at $specCommit."
    dependsOn(downloadSpec)
    inputs.property("sha256", specSha256)
    from(tarTree(resources.gzip(specTarball))) {
        include("*/packages/spec/schema/**", "*/packages/spec/conformance/**", "*/packages/spec/examples/**")
        eachFile { path = path.substringAfter("/packages/spec/") }
        includeEmptyDirs = false
    }
    into(specDir)
    val expected = specSha256
    val dir = specDir
    doLast {
        // GitHub does not promise byte-identical archives, so the digest covers the files:
        // SHA-256 over "<relative path>\n<SHA-256 of the file>\n", paths sorted.
        val root = dir.get().asFile
        val digest = MessageDigest.getInstance("SHA-256")
        root.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .forEach { (path, file) ->
                val fileHash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it) }
                digest.update("$path\n$fileHash\n".toByteArray())
            }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expected) {
            "packages/spec at ${root.name} has digest $actual, expected $expected (formkilnSpecSha256)"
        }
    }
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

// Version in /actuator/info. No build time, so builds stay reproducible and cacheable.
springBoot {
    buildInfo {
        excludes.set(setOf("time"))
    }
}
