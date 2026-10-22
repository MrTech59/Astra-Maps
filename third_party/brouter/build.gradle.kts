// BRouter — vendored runtime modules.
//
// Source: https://github.com/abrensch/brouter, tag v1.7.10, MIT licence.
// Vendored modules (runtime only): brouter-core, brouter-util, brouter-codec,
// brouter-expressions, brouter-mapaccess.
//
// These 5 modules have **no external dependencies** (only `java.*` and `btools.*`)
// and **no resources**, so they are vendored as a single flat source tree.
//
// This module is a plain `java-library` (not an Android library) so it can also
// be exercised by host-side JVM tests. It targets Java 11 bytecode; the app
// module targets 17 and consumes it happily.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(11)
    options.encoding = "UTF-8"
    // BRouter is third-party code we do not control; do not fail the build on
    // its warnings, but keep them visible.
    options.compilerArgs.add("-Xlint:-options")
}

dependencies {
    // Intentionally empty — see the header comment.
}
