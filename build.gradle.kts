import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.integration.test)
}

group = "paceline"
version = "0.0.1-SNAPSHOT"

val frontendDirectory = layout.projectDirectory.dir("frontend")
val frontendNodeModulesDirectory = frontendDirectory.dir("node_modules")
val frontendDistDirectory = frontendDirectory.dir("dist")

val frontendInstall by tasks.registering(Exec::class) {
    workingDir(frontendDirectory)
    commandLine("npm", "ci")
    inputs.files(
        frontendDirectory.file("package.json"),
        frontendDirectory.file("package-lock.json"),
    )
    outputs.dir(frontendNodeModulesDirectory)
}

val frontendBuild by tasks.registering(Exec::class) {
    dependsOn(frontendInstall)
    workingDir(frontendDirectory)
    commandLine("npm", "run", "build")
    inputs.dir(frontendDirectory.dir("src"))
    inputs.dir(frontendDirectory.dir("public"))
    inputs.files(
        frontendDirectory.file("index.html"),
        frontendDirectory.file("vite.config.ts"),
        frontendDirectory.file("tsconfig.json"),
        frontendDirectory.file("package.json"),
        frontendDirectory.file("package-lock.json"),
    )
    outputs.dir(frontendDistDirectory)
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(frontendBuild)
    from(frontendDistDirectory) {
        into("static")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.jmdns)
    implementation(libs.bluez.dbus)
    implementation(libs.dbus.java.transport.native.unixsocket)
    implementation(libs.reactive.streams)
    implementation(libs.garmin.fit)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

ktlint {
    version.set(
        libs.versions.ktlint.engine
            .get(),
    )
}

tasks.withType<Test> {
    useJUnitPlatform()
}
