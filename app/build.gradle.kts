plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Идентификатор приложения в Google Play. ПОСЛЕ ПЕРВОЙ ПУБЛИКАЦИИ МЕНЯТЬ НЕЛЬЗЯ.
// Выбран в ветке music (com.teamcargo.harmonia). Идентификатор com.example.* Google Play не принимает: сборка bundleRelease остановится.
val playApplicationId = "com.teamcargo.harmonia"

android {
    namespace = "com.example.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = playApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 40
        versionName = "2.1.0"
        // Только телефонные процессоры
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Метка сборки: ветка и короткий номер коммита (в CI берутся из GitHub)
        buildConfigField("String", "BUILD_TAG", "\"${(System.getenv("GITHUB_REF_NAME") ?: "local").substringAfterLast('/')} ${(System.getenv("GITHUB_SHA") ?: "").take(7)}\"")
        // Необязательный сервер разметки по метаданным. Ключ ИИ живёт на сервере, в приложении его нет.
        // Пустой адрес выключает серверный этап: локальный анализ звука работает всегда.
        buildConfigField("String", "MUSIC_INTELLIGENCE_URL", "\"${System.getenv("MUSIC_INTELLIGENCE_URL") ?: ""}\"")
        buildConfigField("String", "MUSIC_INTELLIGENCE_TOKEN", "\"${System.getenv("MUSIC_INTELLIGENCE_TOKEN") ?: ""}\"")
    }

    // Подпись релиза: ключ и пароли берутся из переменных окружения (в CI из секретов), в репозитории их нет
    val releaseStoreFile = System.getenv("RELEASE_STORE_FILE")
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Для Google Play нужен App Bundle: он сам режет приложение под каждый телефон
    bundle {
        language { enableSplit = false }
    }
}

// Защита от случайной публикации с учебным идентификатором
tasks.matching { it.name == "bundleRelease" }.configureEach {
    doFirst {
        check(!playApplicationId.startsWith("com.example")) {
            "Google Play не принимает идентификатор com.example.*. Задай свой в playApplicationId (app/build.gradle.kts)."
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
