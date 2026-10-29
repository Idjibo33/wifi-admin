plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

dependencies {
    implementation(project(":router-core"))
    implementation(libs.okhttp)
    implementation(libs.org.json)

    testImplementation(libs.junit)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("dev.wifiadmin.poc.MainKt")
}
