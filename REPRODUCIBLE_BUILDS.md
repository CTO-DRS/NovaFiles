# NovaFiles — البناء القابل لإعادة الإنتاج (Reproducible Builds)

> بند v0.37.0 «جسر الإطلاق»: تثبيت كل إصدارات الاعتماديات، APK حتمي، وتوثيق خطوة التحقق.
> المرجع: [reproducible-builds.org](https://reproducible-builds.org/)

## 1. سلسلة الأدوات المثبّتة

| المكون | الإصدار | مصدر التثبيت |
|---|---|---|
| JDK | **Temurin 17** (مطلوب — Java 21 يكسر JdkImageTransform مع AGP 8.7.3) | adoptium.net |
| Android Gradle Plugin | **8.7.3** | `gradle/libs.versions.toml` → `[versions] agp` |
| Kotlin | **2.0.21** | `gradle/libs.versions.toml` → `kotlin` |
| Gradle (wrapper) | كما في `gradle/wrapper/gradle-wrapper.properties` | توزيع Gradle |
| compileSdk / targetSdk | **35** | `app/build.gradle.kts` |

## 2. تثبيت الاعتماديات

- **كل الإصدارات مثبّتة نصياً** في `gradle/libs.versions.toml` — لا `+` ولا `SNAPSHOT` ولا نطاقات `[1.0,2.0)`.
- قاعدة `core/buildqa/BuildMaturityEngine` تفرض ذلك في اختبار `BuildMaturityTest` (يعمل في كل بناء على الملف الحقيقي). أي إصدار ديناميكي = بناء أحمر.

## 3. أوامر البناء القياسية

```bash
JAVA_HOME=/path/to/jdk17 ./gradlew \
  testDebugUnitTest assembleDebug --no-daemon \
  -Pkotlin.compiler.execution.strategy=in-process
```

عوامل الحتمية:
- `--no-daemon` — يمنع تسرب حالة الذاكرة بين الجولات.
- `-Pkotlin.compiler.execution.strategy=in-process` — ترجمة داخل العملية نفسها، بلا خادم Kotlin خارجي يختلف طابعه.
- بيئة نظيفة: امسح `app/build/` قبل التحقق (`./gradlew clean`).

## 4. خطوة التحقق

1. بناء مرتان من نفس الالتزام (commit) في مجلدين مختلفين:

```bash
./gradlew clean assembleDebug --no-daemon
sha256sum app/build/outputs/apk/debug/app-debug.apk   # سجّل القيمة
./gradlew clean assembleDebug --no-daemon
sha256sum app/build/outputs/apk/debug/app-debug.apk   # قارن
```

2. توقيع وإثبات:

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app-debug.apk
$ANDROID_HOME/build-tools/35.0.0/aapt2 dump badging app-debug.apk | head -3
```

3. للنشر (release/AAB) يُستخدم التوقيع في CI بذاكرة مفاتيح ثابتة، ويُحفظ بصمة الشهادة في GitHub Releases — المستخدمون يستطيعون إعادة البناء ومطابقة `apksigner verify --print-certs`.

## 5. حدود معروفة موثقة

- **APK الـ debug** يحمل كلمة `debuggable` وبتاً زمنياً في ملفات dex الأدوات المساعدة؛ الحتمية الكاملة مستهدفة **للـ release** (R8 full mode) قبل v1.0 — وهو هدف القياس في الخارطة.
- أي انحراف بين جولتي بناء release يُفتح له issue ويُعالج قبل الإطلاق العام (بوابة الجودة النهائية في v1.0 تتضمن «شهادة بناء قابل لإعادة الإنتاج»).
