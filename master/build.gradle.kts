plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "1.9.22"
    application
}

application {
    mainClass.set("gfs.master.MasterServerKt")
}

dependencies {
    implementation(project(":common"))
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}
