import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Verzija aplikacije prati datoteku VERSION u korijenu repozitorija (npr. 1.4.0 -> versionCode 10400).
val appVersion: String = rootProject.file("../VERSION").takeIf { it.exists() }?.readText()?.trim() ?: "0.0.0"
val versionParts = appVersion.split(".").map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)

// Potpisivanje: podaci dolaze iz Gradle svojstava (-P...) koja CI puni iz GitHub tajni.
val signing = Properties().apply {
    listOf("keystoreFile", "keystorePassword", "keyAlias", "keyPassword").forEach { key ->
        (project.findProperty("sweetspot.$key") as String?)?.let { setProperty(key, it) }
    }
}
val hasSigning = signing.getProperty("keystoreFile")?.let { file(it).exists() } == true

android {
    namespace = "io.github.moordrak.sweetspot"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.moordrak.sweetspot"
        minSdk = 31
        targetSdk = 35
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = appVersion
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = file(signing.getProperty("keystoreFile"))
                storePassword = signing.getProperty("keystorePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf("MissingTranslation", "GradleDependency", "NewerVersionAvailable", "OldTargetApi", "AndroidGradlePluginVersion")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
