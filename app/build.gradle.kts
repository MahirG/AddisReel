plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android { namespace = "et.addisreel.app"; compileSdk = 35
 defaultConfig { applicationId = "et.addisreel.app"; minSdk = 24; targetSdk = 35; versionCode = 19; versionName = "1.0.0" }
 signingConfigs {
  create("production") {
   val keystorePath = System.getenv("ADDISREEL_KEYSTORE_PATH")
   if (!keystorePath.isNullOrBlank()) {
    storeFile = file(keystorePath)
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
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 buildFeatures { compose = true }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.material3:material3")
 implementation("io.coil-kt:coil-compose:2.7.0")
 implementation("androidx.compose.material:material-icons-extended")
 implementation("androidx.compose.foundation:foundation")
 implementation("androidx.media3:media3-exoplayer:1.5.1")
 implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
 implementation("androidx.media3:media3-datasource:1.5.1")
 implementation("androidx.media3:media3-database:1.5.1")
 implementation("androidx.media3:media3-ui:1.5.1")
 implementation("androidx.core:core-ktx:1.15.0")
 implementation("androidx.core:core-splashscreen:1.0.1")
 testImplementation("junit:junit:4.13.2")
}