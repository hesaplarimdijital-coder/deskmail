plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// The fixed signing key. CI decodes it from the repository's secrets and points these variables at
// it; the key itself never enters the repository. Without them the build signs with the debug key
// of the machine, and such an APK cannot be installed over one signed with the fixed key.
val signingStoreFile = providers.environmentVariable("DESKMAIL_KEYSTORE_FILE").orNull?.takeIf { it.isNotBlank() }
val signingStorePassword = providers.environmentVariable("DESKMAIL_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "com.deskmail"
    compileSdk = 37

    defaultConfig {
        // never changes: Android treats another id as another app, and the OAuth redirect is built on it
        applicationId = "com.deskmail"
        minSdk = 34
        targetSdk = 36
        // grows by one with every release, pre-releases included; the name is the release's tag
        versionCode = 3
        versionName = "0.1.0-alpha.3"
    }

    signingConfigs {
        if (signingStoreFile != null && signingStorePassword != null) {
            create("fixed") {
                storeFile = file(signingStoreFile)
                storeType = "pkcs12"
                storePassword = signingStorePassword
                keyAlias = "deskmail"
                keyPassword = signingStorePassword
            }
        }
    }

    buildTypes {
        val fixed = signingConfigs.findByName("fixed")
        debug {
            if (fixed != null) signingConfig = fixed
        }
        release {
            // No shrinking: one moving part fewer for an app that is updated once a year, and
            // libraries that find their classes by name keep working.
            isMinifyEnabled = false
            signingConfig = fixed ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // the release APK is what CI builds for every commit; lint is not part of that build
        checkReleaseBuilds = false
    }

    packaging {
        resources {
            // The mail libraries (Jakarta Mail and Activation, Angus) each bring their licence and
            // notice under the same name: all of them go into the APK, one after the other.
            merges += setOf("META-INF/LICENSE.md", "META-INF/NOTICE.md")
        }
    }

    testOptions {
        unitTests {
            // Robolectric draws the real screens: it needs the app's resources and manifest
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                // Robolectric 4.17 reads FileDescriptor internals on Java 17+
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
                val screenshots = layout.buildDirectory.dir("outputs/screenshots")
                it.systemProperty("deskmail.screenshotDir", screenshots.get().asFile.path)
                // declared so a test result restored from the build cache brings the screenshots along
                it.outputs.dir(screenshots).withPropertyName("screenshots")
                it.testLogging {
                    events("failed")
                    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
                }
            }
        }
    }
}

// The database's schema is written to app/schemas, one file per version, and kept in the
// repository: a migration is tested against the schema it starts from.
room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))
    // Brings Compose (BOM 2026.09.00), Navigation and Haze at the versions it was built with.
    implementation(libs.desktop.kit)
    implementation(libs.activity.compose)
    // FileProvider, to share the log as a file
    implementation(libs.androidx.core)
    // The database, on the SQLite the device has
    implementation(libs.room.runtime)
    implementation(libs.sqlite.framework)
    ksp(libs.room.compiler)
    // Coroutines with Android's main thread; the same version :core is built with
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    // an IMAP server inside the tests: the sync is run against it, into the real database
    testImplementation(libs.greenmail)
    testImplementation(libs.robolectric)
    testImplementation(libs.test.core)
    testImplementation(libs.test.ext.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // the empty activity the Compose tests draw in; only in the debug variant the tests run on
    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.test.manifest)
}
