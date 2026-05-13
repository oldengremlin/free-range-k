import org.gradle.api.file.DuplicatesStrategy

plugins {
    kotlin("jvm") version "2.1.20"
    application
}

group = "net.ukrhub.noc"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.jcraft:jsch:0.1.55")
    implementation("info.picocli:picocli:4.7.6")
    implementation("org.yaml:snakeyaml:2.3")
    implementation("org.apache.logging.log4j:log4j-api:2.24.3")
    implementation("org.apache.logging.log4j:log4j-core:2.24.3")
    implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.24.3")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("net.ukrhub.noc.freerange.MainKt")
}

tasks.jar {
    archiveBaseName.set("free-range")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    manifest {
        attributes["Main-Class"] = "net.ukrhub.noc.freerange.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
