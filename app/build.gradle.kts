plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.benzn.grandtime"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.benzn.grandtime"
        minSdk = 33
        targetSdk = 33
        versionCode = 4
        versionName = "0.5.0"
        buildConfigField("String", "COGNITO_POOL_ID", "\"ap-southeast-2_q88pd6XXr\"")
        buildConfigField("String", "COGNITO_CLIENT_ID", "\"4ratjdjonqm17tln6bs2761ci3\"")
        buildConfigField("String", "COGNITO_REGION", "\"ap-southeast-2\"")
    }

    flavorDimensions += "env"
    productFlavors {
        create("prod") {
            dimension = "env"
            isDefault = true // default-selected variant; keeps assembleRelease/Android Studio from picking dev
            // Production org gateway — the customer lake. Shipping/release build.
            buildConfigField("String", "ORG_API_BASE_URL", "\"https://ys94qy2tk0.execute-api.ap-southeast-2.amazonaws.com/prod/api\"")
            // Site voice WebSocket API (prod). Dark-launched: disabled until PROD_ENABLE_SITE_VOICE flip.
            buildConfigField("String", "SITE_VOICE_WS_URL", "\"wss://REPLACE_PROD_WS_ID.execute-api.ap-southeast-2.amazonaws.com/prod\"")
            buildConfigField("boolean", "SITE_VOICE_ENABLED", "false")
        }
        create("dev") {
            dimension = "env"
            applicationIdSuffix = ".dev"   // dev installs side-by-side, never overwrites the prod app
            // Test gateway (fieldsight-test) — safe testing + SP-Ask voice endpoint.
            buildConfigField("String", "ORG_API_BASE_URL", "\"https://wdsgobb7b0.execute-api.ap-southeast-2.amazonaws.com/prod/api\"")
            // Site voice WebSocket API (fieldsight-test). Enabled for soak testing.
            buildConfigField("String", "SITE_VOICE_WS_URL", "\"wss://i1r3tuv9bh.execute-api.ap-southeast-2.amazonaws.com/prod\"")
            buildConfigField("boolean", "SITE_VOICE_ENABLED", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.datastore.preferences)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.security.crypto)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // 保证 JVM 单测能直接用 PreferenceDataStoreFactory(KMP jvm 变体)
    testImplementation(libs.datastore.preferences.core)
    // 真实 org.json 实现,覆盖 android.jar 里抛 "not mocked" 的桩,供 JwtDecoder 单测使用
    testImplementation("org.json:json:20240303")
}
