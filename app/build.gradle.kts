plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.trinhdoc.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "vn.trinhdoc.app"
        minSdk = 30
        targetSdk = 35
        versionCode = 5
        versionName = "2.0"
    }

    // Khoá ký cố định để cài bản mới đè lên bản cũ mà không phải gỡ app.
    signingConfigs {
        create("mera") {
            storeFile = file("mera.jks")
            storePassword = "meraoffice"
            keyAlias = "mera"
            keyPassword = "meraoffice"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("mera")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("mera")
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
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2g" }
    }
}

// App không dùng thư viện ngoài (chỉ Android SDK + Kotlin). Thư viện dưới đây chỉ để kiểm thử.
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
