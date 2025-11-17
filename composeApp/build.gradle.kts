import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)

            implementation("org.jetbrains.compose.material:material-icons-core:1.6.11")
            implementation("org.jetbrains.compose.material:material-icons-extended:1.6.11")

            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.9.5")
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.9.5")

            implementation("io.github.vinceglb:filekit-core:0.12.0")
            implementation("io.github.vinceglb:filekit-dialogs:0.12.0")
            implementation("io.github.vinceglb:filekit-dialogs-compose:0.12.0")
       }
        commonTest.dependencies {
            implementation(kotlin("test-junit5"))
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
        }
    }
}


compose.desktop {
    application {
        mainClass = "com.j4cbo.player.MainKt"

        buildTypes.release.proguard {
            configurationFiles.from(project.file("proguard.cfg"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "EtherDreamPlayer"
            packageVersion = "1.0.1"

            macOS {
                packageName = "EtherDreamPlayer"
                bundleID = "com.j4cbo.player"

                signing {
                    sign.set(true)
                    identity.set("Jacob Potter")
                }
            }

            windows {
                menu = true
                dirChooser = true
                upgradeUuid = "7A7579EC-4351-4982-A64B-F59CE3AF9EF3"
            }
        }
    }
}
