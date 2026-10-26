plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yuxuan.lingo"
    compileSdk = 35
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "com.yuxuan.lingo"
        minSdk = 26
        targetSdk = 35
        versionCode = 36
        versionName = "2.0.11"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
    signingConfigs {
        getByName("debug") {
            // The same debug certificate as v1.x. Keeping the key byte-identical
            // lets existing installs update in place; never regenerate this file.
            // A fresh checkout uses Android's normal local debug key. Existing
            // installs must keep this private key locally for compatible updates.
            val existingKey = rootProject.file("keystore/wordwise-debug.keystore")
            if (existingKey.isFile) storeFile = existingKey
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        release {
            // Retain the existing installation certificate for in-place updates.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Large derived assets are excluded from Git; rebuild them from tracked inputs.
val prepareVocabulary by tasks.registering(Exec::class) {
    workingDir(rootProject.projectDir)
    commandLine(providers.gradleProperty("pythonExecutable").getOrElse("python3"), "tools/prepare_vocabulary.py")
    inputs.files(rootProject.file("tools/prepare_vocabulary.py"), file("src/main/assets/vocabulary.json"), file("src/main/assets/dictionary_sources.json"))
    inputs.dir(file("schemas"))
    outputs.files(file("src/main/assets/prepared_vocabulary.db"), file("src/main/assets/prepared_vocabulary.bin"), file("src/main/assets/prepared_vocabulary.json"))
}
tasks.named("preBuild") { dependsOn(prepareVocabulary) }

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
