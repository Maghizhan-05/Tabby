import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Supabase credentials come from local.properties (gitignored) so no key is
 * committed. Absent values leave the app buildable and runnable: the client
 * reports itself unconfigured instead of crashing on launch.
 *
 * Loaded at the top level rather than inside `android { }`, where the `java`
 * identifier resolves to the Java extension and shadows the package name.
 */
val localProperties = Properties()
rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use {
    localProperties.load(it)
}


android {
    namespace = "com.maghizhan.tabby"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.maghizhan.tabby"
        // minSdk 26 per the approved proposal: Glance and the adaptive-icon /
        // notification-channel APIs the replica needs are all 26+.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "SUPABASE_URL",
            "\"${localProperties.getProperty("supabase.url", "")}\""
        )
        buildConfigField(
            "String",
            "SUPABASE_PUBLISHABLE_KEY",
            "\"${localProperties.getProperty("supabase.publishableKey", "")}\""
        )
    }

    sourceSets {
        // MigrationTestHelper loads the exported schema JSON through the
        // Context's assets, and Robolectric serves assets from the variant
        // under test — NOT from the test source set — so the schemas are
        // attached to each variant that has unit tests. Without this the helper
        // cannot find 1.json and the migration ships untested.
        //
        // Both variants, not just debug: `test` runs testDebugUnitTest AND
        // testReleaseUnitTest, so debug-only assets make the release run fail.
        // The cost is a few KB of JSON in the APK, which is the right trade for
        // having the migration actually verified.
        getByName("debug") {
            assets.srcDir("$projectDir/schemas")
        }
        getByName("release") {
            assets.srcDir("$projectDir/schemas")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

// Room's generated schema JSON is checked in so migrations can be reviewed in
// diffs rather than discovered at runtime.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    // Glance: the home-screen widget. Runs in this same process (no App Group
    // equivalent is needed on Android), so it reads the very same Room
    // database the app writes.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.auth)
    // CIO rather than the OkHttp engine: Ktor's OkHttp engine pulls
    // okhttp-android 5.5.0, which requires compileSdk 37, above the maximum AGP
    // 8.13 supports. CIO is pure Kotlin and has no Android API floor.
    implementation(libs.ktor.client.cio)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric so the Room DAO owner-isolation rules are tested against a
    // real SQLite database on the JVM, not a hand-written fake that could agree
    // with a wrong query.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
