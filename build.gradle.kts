plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    id("org.jetbrains.compose") version "1.9.3" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.21" apply false
}
