plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.novafiles.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.novafiles.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.17.0"

        // v0.16 — معرّفات عملاء OAuth للسحابة: يضبطها كل مُطوّر عبر gradle.properties
        // (NOVA_DRIVE_CLIENT_ID=...) — فارغة افتراضياً ليعمل البناء دون أسرار
        buildConfigField("String", "DRIVE_CLIENT_ID", "\"${project.findProperty("NOVA_DRIVE_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "DROPBOX_CLIENT_ID", "\"${project.findProperty("NOVA_DROPBOX_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"${project.findProperty("NOVA_ONEDRIVE_CLIENT_ID") ?: ""}\"")
    }

    buildTypes {
        release {
            // v0.17 — R8 full mode: تقليص شامل + إزالة الموارد غير المستخدمة
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/{INDEX.LIST,DEPENDENCIES,LICENSE,LICENSE.txt,NOTICE,NOTICE.txt}"
        }
    }
}

dependencies {
    // الأساسيات
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose + Material 3
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // التنقل
    implementation(libs.androidx.navigation.compose)

    // قاعدة البيانات (المفضلة والوسوم) — Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // الإعدادات — DataStore
    implementation(libs.androidx.datastore.preferences)

    // القفل الحيوي للخزنة
    implementation(libs.androidx.biometric)

    // v0.17 — Baseline Profiles: تثبيت مسبق لمسار بدء التطبيق (بلا JIT)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    // صور مصغرة للوسائط — Coil
    implementation(libs.coil.compose)

    // كوروتينات
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)

    // عميل الشبكة: FTP (Apache Commons Net) و SMB2/3 (SMBJ) و SFTP (sshj)
    implementation(libs.commons.net)
    implementation(libs.smbj)
    implementation(libs.sshj)
    implementation(libs.okhttp) // v0.13 — عميل WebDAV
    implementation(libs.slf4j.android)

    // قراءة الأرشيفات: 7z/TAR/GZ/BZ2/XZ (Apache Commons Compress) + RAR (junrar) + مرمز XZ
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.junrar)
    implementation(libs.zxing.core)

    // اختبارات وحدة JVM — مع خادم SSH مدمج لاختبار SFTP حقيقياً
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.sshd.core)
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.slf4j.simple)
}

// رابط slf4j-android يتطلب android.util.Log غير المتاح في اختبارات JVM —
// نستبعده من تهيئات الاختبار ليعمل رابط slf4j-simple بدلاً منه
configurations.configureEach {
    if (name.startsWith("test", ignoreCase = true)) {
        exclude(group = "org.slf4j", module = "slf4j-android")
    }
}
