import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "by.hradovich.ytlite"
    compileSdk = 35

    defaultConfig {
        applicationId = "by.hradovich.ytlite"
        minSdk = 26
        targetSdk = 35
        // versionCode — номер сборки GitHub Actions (для Android),
        // versionName — из CI (с номером хотфикса) или из version.properties
        val build = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        val base = rootProject.file("version.properties").readLines()
            .firstOrNull { it.startsWith("version=") }?.substringAfter('=')?.trim() ?: "0.0.0"
        versionCode = build
        versionName = System.getenv("VERSION") ?: base
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        create("release") {
            val ks = file("release.jks")
            if (ks.exists()) {
                storeFile = ks
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = "ytlite"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Постоянный ключ из секретов GitHub; локально без ключа — debug
            signingConfig = if (file("release.jks").exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.md"
        )
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val media3 = "1.6.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("com.github.teamnewpipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.webkit:webkit:1.12.1")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.4")
}
