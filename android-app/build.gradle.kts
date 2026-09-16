// Root build file. No plugins are applied here — module build files declare
// their own plugins via the version catalog-free `plugins {}` block below.
plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
