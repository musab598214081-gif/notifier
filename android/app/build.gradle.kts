import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

/* Set by the GitHub workflow (repository variables APP_URL and APP_ID). */
val appUrl = (project.findProperty("appUrl") as String?)?.takeIf { it.isNotBlank() } ?: "https://example.com/"
   val appHost = uri(appUrl).host ?: "example.com"
val appId = (project.findProperty("appId") as String?)?.takeIf { it.isNotBlank() } ?: "app.family.notifyer"
val buildNumber = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "app.family.notifyer"
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "APP_URL", "\"$appUrl\"")
        manifestPlaceholders["appHost"] = appHost
    }

    buildFeatures { buildConfig = true }

    /* The upload key, written out by the workflow from repository secrets. */
    val keystore = System.getenv("UPLOAD_KEYSTORE_FILE")
    signingConfigs {
        if (keystore != null && file(keystore).exists()) {
            create("upload") {
                storeFile = file(keystore)
                storePassword = System.getenv("UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS")
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            /* Off on purpose: shrinking would rename the methods the page
               calls through the JavaScript bridges. */
            isMinifyEnabled = false
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-messaging")
}
