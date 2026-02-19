plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("gfs.chunkserver.ChunkServerKt")
}

dependencies {
    implementation(project(":common"))

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}
