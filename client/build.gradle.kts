plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("gfs.client.GfsCliKt")
}

dependencies {
    implementation(project(":common"))

    testImplementation(kotlin("test"))
}
