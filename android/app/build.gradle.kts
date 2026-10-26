import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

val sourceDigest = MessageDigest.getInstance("SHA-256")
val identityInputs = files(
    fileTree("src/main"),
    file("build.gradle.kts"),
    rootProject.file("build.gradle.kts"),
    rootProject.file("settings.gradle.kts"),
    rootProject.file("gradle.properties"),
    rootProject.file("gradle/libs.versions.toml"),
    rootProject.file("gradle/wrapper/gradle-wrapper.properties"),
).files.filter { it.isFile }.sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }
identityInputs.forEach {
    sourceDigest.update(it.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
    sourceDigest.update(0.toByte())
    sourceDigest.update(it.readBytes())
    sourceDigest.update(0.toByte())
}
val sourceFingerprint = sourceDigest.digest().joinToString("") { "%02x".format(it) }

val requestedVersionName = providers.gradleProperty("appVersionName").get()
val requestedVersionCode = providers.gradleProperty("appVersionCode").get()
require(requestedVersionName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))) {
    "appVersionName must be 1..80 characters using letters, digits, dots, underscores or hyphens"
}
val resolvedVersionCode = requestedVersionCode.toIntOrNull()
require(resolvedVersionCode != null && resolvedVersionCode in 1..2_100_000_000) {
    "appVersionCode must be an integer from 1 to 2100000000"
}

val releaseSigningVariables = listOf(
    "REDLINE_KEYSTORE_PATH",
    "REDLINE_KEYSTORE_PASSWORD",
    "REDLINE_KEY_ALIAS",
    "REDLINE_KEY_PASSWORD",
)
val releaseSigningValues = releaseSigningVariables.associateWith {
    providers.environmentVariable(it).orNull
}
val hasReleaseSigning = releaseSigningValues.values.any { !it.isNullOrBlank() }
require(!hasReleaseSigning || releaseSigningValues.values.all { !it.isNullOrBlank() }) {
    "Release signing requires all of: ${releaseSigningVariables.joinToString()}"
}

android {
    namespace = "dev.mrwick.redline"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.mrwick.redline"
        minSdk = 29
        targetSdk = 35
        versionCode = resolvedVersionCode
        versionName = requestedVersionName
        buildConfigField("String", "SOURCE_FINGERPRINT", "\"$sourceFingerprint\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(releaseSigningValues["REDLINE_KEYSTORE_PATH"]))
                require(requireNotNull(storeFile).isFile) { "Release keystore file does not exist" }
                storePassword = releaseSigningValues["REDLINE_KEYSTORE_PASSWORD"]
                keyAlias = releaseSigningValues["REDLINE_KEY_ALIAS"]
                keyPassword = releaseSigningValues["REDLINE_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.graphics.shapes)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.play.services.location)

    implementation(libs.haze)
    implementation(libs.haze.materials)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.compose.ui.text.google.fonts)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.robolectric)
    testImplementation(libs.konsist)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}

roborazzi {
    outputDir.set(project.file("src/test/snapshots/images"))
}
