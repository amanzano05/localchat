plugins {
    id("com.android.application") version "8.7.3" apply false
    // litertlm-android 0.17.0 pulls kotlin-stdlib/kotlin-reflect 2.4.0, so the
    // Kotlin compiler must be >= 2.4.0 (metadata version mismatch otherwise).
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
