import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// :core is pure Kotlin/JVM: no android.* or androidx.* imports. Everything that talks to mail
// servers or decides what to do with their answers lives here, so it is tested on the JVM.
// It runs on Android, so it only uses the parts of java.* that Android has (no java.net.http).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // built with the CI's JDK 21 (the Robolectric tests of :app need it), held to the Java 17 API
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
}
