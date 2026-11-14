// Oberste Ebene. Kotlin bringt das Android-Gradle-Plugin selbst mit (ab AGP 9
// „built-in Kotlin“); hier wird nur die Kotlin-Version angehoben.
buildscript {
    dependencies {
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
}
