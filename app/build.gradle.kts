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

        versionCode = 41
        versionName = "0.1.0"

        // v0.16 — معرّفات عملاء OAuth للسحابة: يضبطها كل مُطوّر عبر gradle.properties
        // (NOVA_DRIVE_CLIENT_ID=...) — فارغة افتراضياً ليعمل البناء دون أسرار
        buildConfigField("String", "DRIVE_CLIENT_ID", "\"${project.findProperty("NOVA_DRIVE_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "DROPBOX_CLIENT_ID", "\"${project.findProperty("NOVA_DROPBOX_CLIENT_ID") ?: ""}\"")
        buildConfigField("String", "ONEDRIVE_CLIENT_ID", "\"${project.findProperty("NOVA_ONEDRIVE_CLIENT_ID") ?: ""}\"")
    }

    // v0.41 — التوقيع عبر متغيرات البيئة فقط: بلا أسرار في المستودع إطلاقاً
    // (F-Droid/CI يبنيان release غير موقّع افتراضياً)
    val envSigning = listOf(
        "NOVA_KEYSTORE_PATH", "NOVA_KEYSTORE_PASSWORD", "NOVA_KEY_PASSWORD"
    ).all { !System.getenv(it).isNullOrEmpty() }
    signingConfigs {
        if (envSigning) {
            create("release") {
                storeFile = file(System.getenv("NOVA_KEYSTORE_PATH"))
                storePassword = System.getenv("NOVA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("NOVA_KEY_ALIAS") ?: "novafiles"
                keyPassword = System.getenv("NOVA_KEY_PASSWORD")
            }
        }
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
            if (envSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
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
            // v0.41 — تعارضات BouncyCastle 1.86 (رفع قسري لإغلاق 11 CVE)
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            excludes += "/META-INF/versions/9/module-info.class"
        }
    }
}

// v0.41 — إغلاق 11 ثغرة CVE في سلسلة BouncyCastle الانتقالية (1.75 → 1.86):
// CVE-2024-29857، CVE-2024-30171، CVE-2024-30172، CVE-2023-33201، CVE-2023-33202
// وغيرها — رفع قسري لكل وحدات BC الثلاث لتجنب خلط إصدارات غير متوافقة
configurations.configureEach {
    resolutionStrategy.force(
        "org.bouncycastle:bcprov-jdk18on:1.86",
        "org.bouncycastle:bcpkix-jdk18on:1.86",
        "org.bouncycastle:bcutil-jdk18on:1.86"
    )
}

dependencies {
    // الأساسيات
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.work.runtime.ktx) // v0.32 — جدولة أزواج المزامنة
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
    // v0.30: إنشاء ZIP بكلمات مرور AES-256 (Zip4j)
    implementation(libs.zip4j)
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
