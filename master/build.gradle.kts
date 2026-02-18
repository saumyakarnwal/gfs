plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("gfs.master.MasterServerKt")
}

dependencies {
    implementation(project(":common"))
}
