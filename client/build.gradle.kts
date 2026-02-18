plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("gfs.client.GfsClientKt")
}

dependencies {
    implementation(project(":common"))
}
