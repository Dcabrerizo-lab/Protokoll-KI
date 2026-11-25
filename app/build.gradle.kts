plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "de.protokollki"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "de.protokollki"
        minSdk = 28
        targetSdk = 36
        // Verkaufsversion (store.yml): eigener, stetig steigender Zähler ab 100000, damit Play-Store-
        // Uploads immer über den Test-Builds liegen. Versionsname aus dem Tag (z. B. v1.2.0 -> 1.2.0).
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        val storeBuild = System.getenv("PK_STORE_BUILD") == "1"
        versionCode = if (storeBuild) 100_000 + run else run
        versionName = System.getenv("PK_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "1.0.$run"

        // Instrumentierte Tests auf dem Gerät/Emulator (app/src/androidTest)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Standard: nur arm64 (alle aktuellen Handys). Für den Emulator-Test: -Ppk.abis=x86_64
        val abis = (project.findProperty("pk.abis") as String?) ?: "arm64-v8a"
        ndk { abiFilters += abis.split(",") }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        create("release") {
            // Fester Schlüssel im Repo, damit Updates sich über die alte Version installieren lassen.
            // Achtung: Schlüssel und Passwort sind öffentlich, also nur für Test- und Demo-Builds.
            storeFile = file("../keystore/protokollki.jks")
            storePassword = "protokollki"
            keyAlias = "protokollki"
            keyPassword = "protokollki"
        }
        // Verkaufsversion: geheimer Schlüssel aus GitHub-Secrets (siehe docs/vertrieb/signatur.md).
        // Ist PK_KEYSTORE_FILE nicht gesetzt, bleibt alles wie bisher (Test-Schlüssel).
        System.getenv("PK_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }?.let { ks ->
            create("store") {
                storeFile = file(ks)
                storePassword = System.getenv("PK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PK_KEY_ALIAS")
                keyPassword = System.getenv("PK_KEY_PASSWORD") ?: System.getenv("PK_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("store") ?: signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    // Testaufnahme (scripts/e2e/meeting.wav) als Asset NUR in der Test-APK, nicht in der App
    sourceSets {
        getByName("androidTest") {
            assets.srcDir("../scripts/e2e")
        }
    }

    testOptions {
        // Für connectedAndroidTest: Systemanimationen aus (stabilere Compose-Tests)
        animationsDisabled = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // doppelte Lizenzdateien aus Test-Bibliotheken (nur Test-APK betroffen)
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/LICENSE-notice.md"
        }
    }
}

kotlin { jvmToolchain(17) }

tasks.withType<Test>().configureEach {
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed")
    }
}

dependencies {
    // sherpa-onnx (Spracherkennung + Sprechererkennung), wird im Build heruntergeladen
    implementation(files("libs/sherpa-onnx.aar"))

    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Text aus importierten PDF-Protokollen (ohne Verschlüsselungs-Unterstützung, spart ~8 MB)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // Instrumentierte Tests (Emulator/Gerät): ./gradlew connectedDebugAndroidTest
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
