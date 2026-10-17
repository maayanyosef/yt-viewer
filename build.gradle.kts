buildscript {
    dependencies {
        // AGP 9 compiles Kotlin itself, using the Kotlin Gradle Plugin it bundles (2.2.10).
        // This line raises that version. Version catalog accessors are not visible inside
        // `buildscript`, so it is written out and checked against the catalog below.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// The Compose compiler plugin is pinned from the version catalog, and it only works with the
// Kotlin compiler of the same version. The buildscript line above cannot read the catalog, so
// the two are compared here instead of being left to drift on the next Kotlin bump.
val kotlinOnBuildscriptClasspath = buildscript.configurations
    .getByName("classpath")
    .dependencies
    .first { it.group == "org.jetbrains.kotlin" && it.name == "kotlin-gradle-plugin" }
    .version

check(kotlinOnBuildscriptClasspath == libs.versions.kotlin.get()) {
    "Kotlin on the buildscript classpath is $kotlinOnBuildscriptClasspath, but `kotlin` in " +
        "gradle/libs.versions.toml is ${libs.versions.kotlin.get()}. Set both to the same version."
}
