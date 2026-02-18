plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("gfs.chunkserver.ChunkServerKt")
}

dependencies {
    implementation(project(":common"))
}
