plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.arcaea.songpack"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.arcaea.songpack"
        minSdk = 26
        targetSdk = 34
        versionCode = 65
        versionName = "6.0.5"
        // 计时日志开关: gradle assembleDebug -Ptiming=true 时输出加载耗时日志(仅用于性能分析)
        val timing = (project.findProperty("timing") as? String) == "true"
        buildConfigField("boolean", "TIMING_LOGS", timing.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 正式版用 debug keystore 签名(与历史版本一致, 便于覆盖安装)
            signingConfig = signingConfigs.getByName("debug")
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "META-INF/DEPENDENCIES"
        resources.excludes += "META-INF/LICENSE*"
        resources.excludes += "META-INF/NOTICE*"
        resources.excludes += "META-INF/INDEX.LIST"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // 下拉刷新(曲包管理页)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    // SAF 文件树访问
    implementation("androidx.documentfile:documentfile:1.0.1")

    // 解压 zip(支持中文文件名/加密)
    implementation("net.lingala.zip4j:zip4j:2.11.5")
    // 解压 rar
    implementation("com.github.junrar:junrar:7.5.5")
}
