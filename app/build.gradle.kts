plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.androidspect"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.androidspect"
        minSdk = 21            // Android 5.0, broadest realistic compat for rooted devices
        targetSdk = 36         // Android 16
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("en")
        multiDexEnabled = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Core library desugaring: lets minSdk 21 use java.time, java.util.function,
        // and java.nio.file APIs that Ktor/Netty pull in.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjvm-default=all")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                // BouncyCastle multi-release JARs ship OSGi metadata that
                // collides between bcprov / bcpkix / bcutil - we never load
                // them through OSGi anyway.
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
        jniLibs {
            useLegacyPackaging = false
        }
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
}

dependencies {
    // AndroidX + Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Coroutines + Serialization
    implementation(libs.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)

    // Ktor server (embedded HTTP + WebSocket)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Root access via libsu (Magisk maintainer's library)
    implementation(libs.libsu.core)
    implementation(libs.libsu.io)
    implementation(libs.libsu.service)

    // MultiDex + core library desugaring for API 21-25 support
    implementation(libs.androidx.multidex)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // BouncyCastle - needed to build the self-signed X.509 cert for our
    // embedded HTTPS server. Android's bundled BC doesn't expose the
    // certificate-builder APIs (X509v3CertificateBuilder etc.).
    implementation(libs.bouncycastle.bcpkix)
}
