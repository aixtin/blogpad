import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing is optional: a missing keystore.properties (or a missing .jks)
 * silently falls back to an unsigned release + debug build, so a fresh clone builds
 * without any private material. Lookup order: repo root, then ~/keys.
 */
val signingProps = Properties().also { props ->
    val candidates = listOf(
        rootProject.file("keystore.properties"),
        File(System.getProperty("user.home"), "keys/keystore.properties")
    )
    candidates.firstOrNull { it.isFile }?.let { file ->
        FileInputStream(file).use { props.load(it) }
    }
}
val releaseStore = signingProps.getProperty("storeFile")?.let { File(it) }
val hasReleaseKey = releaseStore != null && releaseStore.isFile

android {
    namespace = "io.github.aixtin.blogpad"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.aixtin.blogpad"
        minSdk = 26
        targetSdk = 34
        versionCode = 6
        versionName = "1.7.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = releaseStore
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE",
            "META-INF/LICENSE.txt",
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.github.mwiede:jsch:0.2.26")
    implementation("org.bouncycastle:bcprov-jdk18on:1.82")
}
