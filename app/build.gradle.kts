plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseKeystoreFile: String? = System.getenv("KEYSTORE_FILE")
    ?: (project.findProperty("KEYSTORE_FILE") as? String)
val releaseKeystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")
    ?: (project.findProperty("KEYSTORE_PASSWORD") as? String)
val releaseKeyAlias: String? = System.getenv("KEY_ALIAS")
    ?: (project.findProperty("KEY_ALIAS") as? String)
val releaseKeyPassword: String? = System.getenv("KEY_PASSWORD")
    ?: (project.findProperty("KEY_PASSWORD") as? String)

android {
    namespace = "com.callhandler.service"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.callhandler.service"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "1.4.5"
    }

    signingConfigs {
        create("release") {
            if (!releaseKeystoreFile.isNullOrBlank()) {
                val store = file(releaseKeystoreFile)
                if (store.exists()) {
                    storeFile = store
                    storePassword = releaseKeystorePassword
                    keyAlias = releaseKeyAlias
                    keyPassword = releaseKeyPassword
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

gradle.taskGraph.whenReady {
    val isReleaseBuildRequested = allTasks.any { task ->
        val taskName = task.name.lowercase()
        taskName.contains("release") && (
            taskName.contains("assemble") ||
            taskName.contains("bundle") ||
            taskName.contains("package") ||
            taskName.contains("sign")
        )
    }

    if (isReleaseBuildRequested) {
        val missing = mutableListOf<String>()
        if (releaseKeystoreFile.isNullOrBlank()) {
            missing.add("KEYSTORE_FILE")
        } else if (!file(releaseKeystoreFile).exists()) {
            missing.add("KEYSTORE_FILE (file '$releaseKeystoreFile' does not exist)")
        }
        if (releaseKeystorePassword.isNullOrBlank()) {
            missing.add("KEYSTORE_PASSWORD")
        }
        if (releaseKeyAlias.isNullOrBlank()) {
            missing.add("KEY_ALIAS")
        }
        if (releaseKeyPassword.isNullOrBlank()) {
            missing.add("KEY_PASSWORD")
        }

        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release build failed: Missing or invalid required signing credentials:\n  - " +
                missing.joinToString("\n  - ") +
                "\nProvide them via environment variables (KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) " +
                "or Gradle properties (-PKEYSTORE_FILE=...)."
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
}
