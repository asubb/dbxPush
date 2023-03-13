plugins {
    kotlin("jvm") version "1.8.10"
    kotlin("plugin.serialization") version "1.8.10"
    application
}

application {
    mainClass.set("dbx.push.PushKt")
    applicationName = "dbxpush"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0-Beta")

    implementation("commons-cli:commons-cli:1.4")
    implementation("com.dropbox.core:dropbox-core-sdk:3.1.4")
    implementation("dev.failsafe:failsafe:3.3.0")
}

