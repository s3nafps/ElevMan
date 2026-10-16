plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.elevman"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.elevman"
        // Android 8.0. At minSdk 26 the build emits dex 038, which Android 8 can read. minSdk 29 emitted
        // dex 039, which needs Android 9 or later. java.time is part of the platform from API 26, so no
        // desugaring is needed.
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // The release key is never stored in the repository. CI or a local build supplies it through
    // environment variables. Without ELEVMAN_KEYSTORE_PATH the release APK falls back to the debug
    // key so it can still be installed for testing, and Gradle prints a warning.
    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ELEVMAN_KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ELEVMAN_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ELEVMAN_KEY_ALIAS")
                keyPassword = System.getenv("ELEVMAN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("ELEVMAN_KEYSTORE_PATH") != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("ELEVMAN_KEYSTORE_PATH is not set: the release APK is signed with the debug key (test builds only).")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs merged resources and the manifest on the unit-test classpath.
            isIncludeAndroidResources = true
            all {
                // Robolectric's Android 16/17 framework shadows reach into JDK internals
                // (jdk.internal.access.SharedSecrets) and fail on JDK 17+ without this flag.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    // Room writes its schema JSON here (ElevManDatabase uses exportSchema = true).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)

    // JVM unit tests (Robolectric runs Room against a real SQLite on the JVM).
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)

    // Compose UI tests also run on the JVM under Robolectric (no emulator needed).
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Asked for by the Task 1 review for a release unit-test variant. AGP 9.4 does not create that
    // variant (there is only testDebugUnitTest), and createComposeRule() gets its ComponentActivity
    // from debugImplementation below, so this line has no effect today.
    testImplementation(libs.androidx.compose.ui.test.manifest)

    // Instrumented tests (need a device or emulator running Android 8.0 or later).
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
