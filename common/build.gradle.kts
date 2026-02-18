val grpcVersion: String by project
val coroutinesVersion: String by project

plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":proto"))
    api("io.grpc:grpc-netty-shaded:$grpcVersion")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
}
