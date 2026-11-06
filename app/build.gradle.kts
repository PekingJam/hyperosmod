plugins {
    id("com.android.application")
}

val releaseKeystore = rootProject.file("keystore.jks")
val hasReleaseKeystore = releaseKeystore.exists()

android {
    namespace = "com.hyperosmod"
    compileSdk = 37
    compileSdkMinor = 0
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.hyperosmod"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD").orEmpty()
                keyAlias = System.getenv("KEY_ALIAS").orEmpty()
                keyPassword = System.getenv("KEY_PASSWORD").orEmpty()
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // A real release key can be supplied by CI secrets. Otherwise use the
            // debug key so that every build still produces an installable APK.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    lint {
        abortOnError = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric writes its download lock/cache under user.home. Keep that inside
            // the project so sandboxed builds (and CI) do not depend on a writable HOME.
            val testHome = layout.buildDirectory.dir("test-home").get().asFile.apply { mkdirs() }
            val testTmp = layout.buildDirectory.dir("test-tmp").get().asFile.apply { mkdirs() }
            it.systemProperty("user.home", testHome.absolutePath)
            it.systemProperty("java.io.tmpdir", testTmp.absolutePath)
            it.systemProperty("robolectric.offline", "true")
            it.systemProperty("robolectric.dependency.dir",
                project.layout.buildDirectory.dir("robolectric-deps").get().asFile.absolutePath)
        }
    }
}

val robolectricDeps = configurations.create("robolectricDeps")

dependencies {
    // libxposed API 102: provided by LSPosed at runtime, compile only.
    compileOnly("io.github.libxposed:api:102.0.0")
    // Used by the manager app to obtain the remote SharedPreferences bridge.
    implementation("io.github.libxposed:service:102.0.0")

    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("com.google.android.material:material:1.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    robolectricDeps("org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}

val copyRobolectricDeps = tasks.register<Copy>("copyRobolectricDeps") {
    from(robolectricDeps)
    into(layout.buildDirectory.dir("robolectric-deps"))
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach {
    dependsOn(copyRobolectricDeps)
}
