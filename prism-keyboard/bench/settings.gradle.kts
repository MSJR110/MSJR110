// A plain-JVM build for the typing benchmark and the language-model builder.
// It compiles the keyboard's text engine straight from the app sources, so it needs neither the
// Android Gradle plugin nor a device: just Kotlin and JUnit from Maven Central.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
rootProject.name = "prism-bench"
