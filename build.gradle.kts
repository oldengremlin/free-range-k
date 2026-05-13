plugins {
    kotlin("jvm") version "2.0.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
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

tasks.shadowJar {
    archiveBaseName.set("free-range")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    manifest { attributes["Main-Class"] = "net.ukrhub.noc.freerange.MainKt" }
}
