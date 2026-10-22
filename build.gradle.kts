// Astra Maps — root build script.
//
// Toolchain (locked, see docs/ARCHITECTURE.md):
//   Gradle 8.13 · AGP 8.12.0 · Kotlin 2.2.10 · KSP 2.2.10-2.0.2 · JDK 21
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
