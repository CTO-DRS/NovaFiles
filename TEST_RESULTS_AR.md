# نتائج الاختبارات والبناء — NovaFiles v0.41.1

**التاريخ:** 2026-10-24 | **البيئة:** Gradle 8.9 (يدوي، بلا daemon) + JDK 17.0.21 + SDK 35

## 1. أوامر شُغّلت فعلاً (مع رموز الخروج الحقيقية)

| الأمر | النتيجة | التوقيت |
|---|---|---|
| `gradle testDebugUnitTest` (خط الأساس v0.40.0) | ✅ EXIT=0 — **1,727 اختباراً، 0 فشل** | 6m 52s (مع تنزيل كل الاعتماديات) |
| `gradle testDebugUnitTest` (نهائي v0.41.0) | ✅ EXIT=0 — **122 حزمة / 1,779 اختباراً / 0 فشل / 0 تخطي** | 1m 32s |
| `gradle testDebugUnitTest` (بعد رقعة الختم الأولية v0.41.1) | ❌ EXIT=1 — **فشل 1**: بوابة StringsParity لم تتعرف على `formatted="false"` → تطوير محرك التكافؤ + اختبار | ~4m |
| `gradle testDebugUnitTest` (نهائي v0.41.1) | ✅ EXIT=0 — **123 حزمة / 1,797 اختباراً / 0 فشل / 0 تخطي** | ~4m |
| `gradle lint` (أول تشغيل فعلي في تاريخ المشروع) | ❌ EXIT=1 — **19 خطأ / 197 تحذيراً** (كلها سابقة عن هذه الرقعة) | 4m 56s |
| `gradle lint` (بعد إصلاح 12 خطأ) | ❌ EXIT=1 — **7 أخطاء / 191 تحذيراً** متبقية (موثقة أدناه) | ~5m |
| `gradle assembleDebug` | ✅ EXIT=0 | 50s (ذاكرة دافئة) |
| `aapt2 dump badging` | ✅ `versionCode='42' versionName='0.41.1' minSdk='26' targetSdk='35'` | — |

**حادثة بيئة صادقة:** تشغيل `testDebugUnitTest lint` في استدعاء واحد أفقد شيطان Gradle (قتل ذاكرة على 4GB) — فُصلت الأوامر واستؤنف العمل. لا تأثير على النتائج.

**لم يُشغّل** (مع السبب الصادق):
- `assembleRelease` — يتطلب keystore توقيع من المالك (لا مفاتيح في Git بالتصميم).
- أي اختبار أجهزة (connectedDebugAndroidTest) — لا محاكي في هذه البيئة.

## 2. الاختبارات الجديدة في v0.41.1 (+18)

| الحزمة | العدد | ماذا تغطي |
|---|---|---|
| core.security.ServerPassSealerTest | 17 | ختم/فتح NFCV1 (عربي ورموز)، IV جديد كل عملية، عطب الشفرة → null، مفتاح خاطئ → null، `isSealed`، توافق الصيغة مع خزنة بيانات الاعتماد (الاتجاهان)، القراءة الموحدة (مختوم أولاً/فاسد→افتراضي/بلا مفتاح→فشل مغلق/موروث مؤقتاً/افتراضي)، خطة الترحيل (ختم+إبادة/بلا فعل/فراغ/ختم قائم يفوز/تكرارية) |
| core.l10n.StringsParityTest (اختبار إضافي) | 1 | المحرك يقبل سمات `string` القياسية (formatted/translatable) دون كسر التكافؤ |

خط الأساس 1,779 → **1,797**

## 3. lint — أول تشغيل فعلي (صادق وموثق)

**19 خطأ → 7 بعد إصلاح 12**، والتحذيرات 197 → 191. ما أُصلح:

| الفئة | العدد | الإصلاح |
|---|---|---|
| StringFormatInvalid | 6 | أعطال تشغيل حقيقية — ترجمات fr/es/id فيها `100%` يليها حرف لاتيني فتفسَّر كنسيق وتُبطل العرض (MissingFormatArgumentException عند getString مع سياق). الإصلاح: `formatted="false"` للسلاسل بلا placeholders (guide_s_updates_b id، onboarding_welcome_b es+fr، about_open_source es+id، ai_subtitle fr) |
| NewApi | 4 | Http.kt:225 وOAuthClient.kt:156 (نسخة Charset API 33 → نمط UTF-8 النصي API 1، سلوك مطابق)، ImageBatchExecutor.kt:95 (WEBP_LOSSY ≥30 وإلا WEBP كلاسيكي)، WifiDirectManager.kt:109 (Channel.close ≥27 فقط) |
| PermissionImpliesUnsupportedChromeOsHardware | 1 | `<uses-feature camera required="false">` — الكاميرا اختيارية أصلاً |
| CoarseFineLocation | 1 | إضافة ACCESS_COARSE_LOCATION ≤32 وفق سياسة أندرويد (منح تقريبي بدل رفض كامل) |

**الباقية 7 أخطاء** (سابقة عن الجلسة، تتطلب جهاز اختبار/إعادة هيكلة UI — لا تُخاطَر بها رقعة أمنية):
- MissingPermission ×4: IntrusionCapturer.kt:53 (فتح كاميرا بلا checkPermission صريح)، WifiDirectManager.kt:68/92/116 (نداءات P2P بلا حارس صلاحية صريح — البوابات موجودة في طبقة UI)
- CoroutineCreationDuringComposition: Vault2Sheets.kt:202 — `launch` داخل التكوين بدل LaunchedEffect
- FlowOperatorInvokedInComposition: MainActivity.kt:39 — عامل Flow داخل تكوين
- AppLinkUrlError: AndroidManifest.xml:65 — intent-filter بلا موقع ويب للتحقق
- السجل الكامل: `lint-v0411*.log` و`app/build/.../lint-results-debug.txt` (191 تحذيراً مسجلة كأعمال P2)

## 4. بوابات الجودة المحدثة (نمط المشروع)

- `BuildMaturityTest` → versionName=0.41.1, versionCode=42 ✅
- `StringsParityTest` → أرضية 1,038 مفتاحاً × 10 لغات ✅ (+محرك يقبل السمات القياسية)
- `SettingsExportEngineTest` → قائمة الممنوع من التصدير تضم الآن `ftp_pass_enc`/`dav_pass_enc` ✅
- `A11ySourceAudit` / `ContrastAuditor` — لم تُلمس، نجحت ضمن المجموعة ✅

## 5. المخرجات النهائية

| الملف | الحجم | SHA-256 |
|---|---|---|
| `download/NovaFiles-v0.41.1-debug.apk` | 32.8 MB | `3dc6f9a4defb9befe356526b2158b826140ea728efce4963d7b9a055fa33ed30` |
| `download/NovaFiles-v0.41.1-source.zip` | 1.4 MB | `622d78b9f79931b0871ed1b3726ebeca4402f324f410bd20d11c3df99cca1158` |
| فرع Git: `nova-v0.41.0-upgrade` | رقعة v0.41.1 مضافة على الفرع نفسه | — |

## 6. سجل حالات الفشل الصادقة أثناء التطوير

1. **v0.41.0**: 18 فشلاً (جولة AI الأولى): نطاق `'أ'..'ٱ'` طبع كل الحروف إلى 'ا' → أُصلح بأحرف صريحة + اختبار منع انحدار.
2. **v0.41.1**: فشل StringsParity بعد `formatted="false"` (6 مفاتيح «مفقودة» وهمياً) — المحلل النصي كان يقبل `name` فقط قبل `>`؛ طُوّر النمط إلى `[^>]*` واختُبر، والبوابة عادت خضراء.
3. **v0.41.1**: قتل شيطان Gradle عند تجميع الاختبارات+lint معاً (ذاكرة 4GB) — فصل الأوامر.
4. **فشل lint EXIT=1 متبقٍ**: 7 أخطاء موثقة أعلاه — يُسجل كما هو بلا تمويه أخضر.
