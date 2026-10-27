plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/** 读 gradle.properties / -Pxxx= 传入的字符串配置 */
fun Project.propStr(key: String, fallback: String): String =
    (findProperty(key) as String?).takeUnless { it.isNullOrBlank() } ?: fallback

android {
    namespace = "com.zilizili.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.zilizili.app"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // 从 gradle.properties 或 -PAPI_BASE= / ORG_GRADLE_PROJECT_API_BASE 注入
        val apiBase = (project.findProperty("API_BASE") as String?)
            .takeUnless { it.isNullOrBlank() }
            ?: "https://xfan.ccccocccc.cc/api.php"
        buildConfigField("String", "API_BASE", "\"$apiBase\"")

        // 上传接口令牌（对应服务端 $CFG['token']）
        val upToken = (project.findProperty("UPLOAD_TOKEN") as String?)
            .takeUnless { it.isNullOrBlank() }
            ?: "change-me-please"
        buildConfigField("String", "UPLOAD_TOKEN", "\"$upToken\"")

        // 版本码：用于和 videoupdate.json 里的 versionCode 比较
        buildConfigField("int", "APP_VERSION_CODE", versionCode.toString())
        buildConfigField("String", "APP_VERSION_NAME", "\"$versionName\"")

        // 更新源：GitHub 仓库坐标，App 会据此拼出 raw + 多个代理镜像
        buildConfigField("String", "UPDATE_OWNER", "\"${propStr("UPDATE_OWNER", "gjgffugf")}\"")
        buildConfigField("String", "UPDATE_REPO", "\"${propStr("UPDATE_REPO", "update")}\"")
        buildConfigField("String", "UPDATE_BRANCH", "\"${propStr("UPDATE_BRANCH", "main")}\"")
        buildConfigField("String", "UPDATE_FILE", "\"${propStr("UPDATE_FILE", "videoupdate.json")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-ktx:1.9.0")
}
