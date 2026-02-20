plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.22"
    application
}

application {
    mainClass.set("gfs.master.MasterServerKt")
}

tasks.register<JavaExec>("dumpOplog") {
    mainClass.set("gfs.master.OplogDumpKt")
    classpath = sourceSets["main"].runtimeClasspath
}

dependencies {
    implementation(project(":common"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}
