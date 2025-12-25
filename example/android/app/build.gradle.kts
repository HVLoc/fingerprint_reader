plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

val supportNFIQ2 = true
val needDevicePower = true


android {
    namespace = "vn.lochv.fingerprint_reader_example"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "vn.lochv.fingerprint_reader_example"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 24
        targetSdk = 36
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

        sourceSets {
            getByName("main") {

                // JNI libs chuẩn cho Flutter
                jniLibs.srcDirs(
                    "src/main/jniLibs",
                    "libs/libcore"
                )

                if (supportNFIQ2) {
                    jniLibs.srcDirs("libs/libnfiq2")
                    assets.srcDirs("src/main/assets/nfiq2")
                }

                if (needDevicePower) {
                    jniLibs.srcDirs("libs/libdevicepower")
                }
            }
        }

}

flutter {
    source = "../.."
}

dependencies {
    implementation(files("libs/MIAXIS_Driver.aar"))
    implementation(files("libs/FPR_220_Live.aar"))
    implementation(files("libs/JustouchApi.aar"))
    implementation(files("libs/MxAlgShankshake.aar"))
    implementation("com.android.support:appcompat-v7:26.1.0")
    implementation ("com.android.support.constraint:constraint-layout:1.1.3")
    implementation ("com.android.support:support-v4:26.1.0")
    implementation ("com.android.support:design:26.1.0")
    implementation(files("libs\\TrustFinger_v3.3.0.6.jar"))
    if (needDevicePower) {
        implementation (files("libs\\AraBMApiDev.jar"))
    }
}