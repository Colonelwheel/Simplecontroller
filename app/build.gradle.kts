plugins {
    id("com.android.application")
    kotlin("android")                                       // ← uses 2.0.21 already on class-path
    kotlin("plugin.serialization") version "2.0.21"         // ← add only this one
}

android {
    namespace = "com.example.simplecontroller"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.simplecontroller"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    /* Align Java & Kotlin to the same JVM target */
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            applicationId = "com.example.simplecontroller"
            targetSdk = 34
        }
        create("play") {
            dimension = "distribution"
            applicationId = "io.github.colonelwheel.simplecontroller"
            targetSdk = 36
        }
    }

}

androidComponents {
    beforeVariants(selector().all()) { variantBuilder ->
        val distribution = variantBuilder.productFlavors
            .firstOrNull { it.first == "distribution" }
            ?.second

        variantBuilder.enable = when {
            distribution == "sideload" && variantBuilder.buildType == "debug" -> true
            distribution == "play" && variantBuilder.buildType == "release" -> true
            else -> false
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    /* JSON save / load */
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    /* Kotlin coroutines */
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    /* Android essentials */
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
