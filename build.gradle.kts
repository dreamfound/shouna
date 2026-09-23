// Top-level build file. Plugin versions are declared once in gradle/libs.versions.toml
// and applied per-module with `alias(...)`.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
