plugins { id("com.android.application") }

android {
    namespace = "app.cutiepie.cutie_pie"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.cutiepie.cutie_pie"
        minSdk = 24
        targetSdk = 36
        versionCode = 35
        versionName = "0.4.14"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    flavorDimensions += "capability"
    productFlavors {
        create("normal") {
            dimension = "capability"
            versionNameSuffix = "-normal"
            buildConfigField("boolean", "REMOTE_CONTROL", "false")
        }
        create("assist") {
            dimension = "capability"
            versionNameSuffix = "-assist"
            buildConfigField("boolean", "REMOTE_CONTROL", "true")
        }
    }
    buildTypes { getByName("debug") { versionNameSuffix = "-debug" } }
    testOptions { unitTests.isReturnDefaultValues = false }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("net.zetetic:sqlcipher-android:4.10.0@aar")
    implementation("androidx.sqlite:sqlite:2.5.2")
    implementation("org.whispersystems:signal-protocol-java:2.8.1")
    implementation("com.goterl:lazysodium-android:5.2.0@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.firebase:firebase-messaging:25.1.3")
    implementation("com.google.firebase:firebase-auth:24.2.0")
    implementation("com.google.android.gms:play-services-auth:21.6.0")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.47.2.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
