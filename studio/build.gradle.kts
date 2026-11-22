plugins { id("com.android.application") }
android {
    namespace = "et.addisreel.studio"
    compileSdk = 35
    defaultConfig {
        applicationId = "et.addisreel.studio"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        create("production") {
            val path = System.getenv("ADDISREEL_KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("ADDISREEL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ADDISREEL_KEY_ALIAS")
                keyPassword = System.getenv("ADDISREEL_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("production")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
