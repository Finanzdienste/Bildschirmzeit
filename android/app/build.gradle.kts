plugins {
    alias(libs.plugins.android.application)
}

// Jede APK aus GitHub Actions bekommt die Laufnummer als versionCode – die
// App vergleicht sie mit dem Tag des neuesten Releases („v<Laufnummer>“).
// Lokal gebaut ist es 1.
val laufNummer = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "de.finanzdienste.bildschirmzeit"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.finanzdienste.bildschirmzeit"
        minSdk = 29
        // Android 16 – das, was das S24 heute hat. 37 erst, wenn es auf dem
        // Gerät läuft und die App dort geprüft ist.
        targetSdk = 36
        versionCode = laufNummer
        versionName = "1.$laufNummer"
    }

    signingConfigs {
        // Bewusst öffentlich (README „Signaturschlüssel“): Jede Fassung muss
        // mit demselben Schlüssel signiert sein, damit sie sich über die alte
        // installieren lässt – und gebaut wird ohne Geheimnisse in GitHub
        // Actions.
        create("oeffentlich") {
            storeFile = rootProject.file("signatur/bildschirmzeit.p12")
            storeType = "PKCS12"
            storePassword = "bildschirmzeit-oeffentlich"
            keyAlias = "bildschirmzeit"
            keyPassword = "bildschirmzeit-oeffentlich"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("oeffentlich")
        }
        debug {
            signingConfig = signingConfigs.getByName("oeffentlich")
        }
    }

    // Ab AGP 9 gibt es Unit-Tests nur für den getesteten Build-Typ – geprüft
    // wird, was ausgeliefert wird: testReleaseUnitTest.
    testBuildType = "release"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Die Vorschau-Bilder des Widgets landen im Repo unter docs/.
                it.systemProperty("vorschau.ordner", rootProject.file("../docs").absolutePath)
                it.systemProperty("vorschau.bau", layout.buildDirectory.dir("vorschau").get().asFile.absolutePath)
                it.maxHeapSize = "2g"
            }
        }
    }

    lint {
        // Ohne Play Store: Hinweise auf neuere Bibliotheken oder targetSdk
        // sind kein Grund, den Bau scheitern zu lassen.
        disable += setOf("OldTargetApi", "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.work)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
