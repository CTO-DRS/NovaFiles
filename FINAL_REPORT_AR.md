# التقرير النهائي — الترقية الهندسية الشاملة v0.40.0 → v0.41.0

**التاريخ:** 2026-10-24 | **الفرع:** `nova-v0.41.0-upgrade` | **الالتقاء:** بناء واختبارات ناجحة موثقة

## 1. الملفات التي فُحصت (قراءة مباشرة أو أدوات)
- **Manifest والأمان**: AndroidManifest.xml (183 سطراً)، backup_rules.xml، data_extraction_rules.xml، file_paths.xml
- **الخزنة**: Vault2Manager.kt (244)، KeystoreVault.kt، CredentialVault.kt (توثيقاً)، KnownHostPrefs.kt
- **الإعدادات**: SettingsStore.kt (24 مفتاحاً — سطور الأسرار 44/49)، MainActivity.kt (deep links 79-115)
- **الشبكات**: FtpServerService.kt (auth)، WebDavServerService (وجود)، ReleaseGate.kt
- **البناء**: app/build.gradle.kts، libs.versions.toml (كامل)، gradle.properties
- **المحركات للربط**: SearchEngine، FileIndexer، StorageAnalyzer، DuplicateEngine، RuleModels/RulesRepository، StorageModels، UserGuideScreen (النمط)، IOSBars/IOSUI (الواجهة)
- **فحوص شاملة**: grep للسجلات الحساسة (صفر)، جرد 218+112 ملف Kotlin، عدد السلاسل 1,026→1,038×10

## 2. الملفات المعدلة والجديدة (21 معدلاً + 8 جديدة)

### إصلاحات أمنية P0
| الملف | التغيير | السبب |
|---|---|---|
| `res/xml/data_extraction_rules.xml` | استثناء 5 مخازن أسرار في cloud-backup **وdevice-transfer** | كانت فارغة كلياً — تسريب سحابي مثبت على Android 12+ |
| `res/xml/backup_rules.xml` | نفس الاستثناءات لـ API≤30 | كان يستثني ملاحظات الخزنة فقط |
| `res/xml/file_paths.xml` | حذف `root-path="."` + نطاقات صريحة | FileProvider كان يخدم جذر النظام كله |

### Nova Intelligence (جديدة)
| الملف | الدور |
|---|---|
| `core/ai/AiModels.kt` | الأنواع + القائمة المسموحة (7 أدوات، WRITE يتطلب تأكيداً) |
| `core/ai/ArabicText.kt` | تطبيع عربي حرفي (تشكيل/همزات/أرقام شرقية) |
| `core/ai/IntentParser.kt` | محلل نوايا حتمي عربي/إنجليزي بمعاملات محددة |
| `core/ai/AiToolRegistry.kt` | مواصفات وسيطات مضبوطة النوع + رفض المجهول |
| `core/ai/PlanValidator.kt` | canonical + جذور مسموحة + رفض الحقن والروابط الرمزية |
| `core/ai/ErrorExplainer.kt` | 13 توقيع فشل بشرح عربي وخطوات إصلاح |
| `core/ai/AssistantEngine.kt` | المنسق: نية→خطة→تحقق→دور (بلا تنفيذ كتابة ذاتي) |
| `core/ai/TranscriptCodec.kt` | سجل CSV متسامح (حد 50، مضاد للحقن) |
| `data/prefs/AssistantStore.kt` | سجل اختياري معطّل افتراضياً + مسح عند الإطفاء |
| `ui/assistant/AssistantExecutor.kt` | منفّذ فوق محركات حقيقية (فهرس/تحليل/مكررات/قواعد — قواعد تبدأ dryRun) |
| `ui/assistant/AssistantScreen.kt` | شاشة محادثة iOS + بطاقات خطة + شرائح مخاطر + تبديل خصوصية |

### التكامل والإصدار
| الملف | التغيير |
|---|---|
| `NovaApp.kt` | +3 مزودات (assistant/assistantStore/assistantExecutor) |
| `NovaNavHost.kt` | +مسار ASSISTANT +ربط |
| `SettingsScreen.kt` | +صف «المساعد الذكي» |
| `strings.xml ×10` | +12 مفتاحاً ×10 لغات (1,038 متطابقاً) |
| `app/build.gradle.kts` | versionCode 41 / versionName 0.41.0 |
| `fastlane .../changelogs/41.txt ×10` | سجلات تغيير ثنائية اللغة |
| `BuildMaturityTest` / `StringsParityTest` | بوابات الإصدار محدثة (الإجراء المقصود) |

## 3. العيوب المكتشفة (مثبتة) → الإصلاحات المنفذة
1. **تسريب أسرار إلى النسخ السحابي ونقل الأجهزة (12+)** → استثناء مزدوج + بوابة اختبار آلية
2. **FileProvider يخدم جذر النظام** → نطاقات تفشل بإغلاق + بوابة اختبار
3. **ftp_pass/dav_pass نصاً صريحاً** → حجب من النسخ الآن؛ التشفير الكامل موثق P1 (يستلزم ترحيل)
4. **عيوب داخلية للمساعد أثناء التطوير** (نطاق تطبيع يبتلع الحروف، nullable، أولوية «قاعدة») → أصلحت كلها وقبلها اختبارات

## 4. الاختبارات والبناء (أرقام حقيقية)
- خط الأساس v0.40.0: **1,727/1,727** (EXIT=0، 6m52s)
- النهائي v0.41.0: **122 حزمة / 1,779 اختباراً / 0 فشل / 0 تخطي** (EXIT=0، 1m32s)
- `assembleDebug`: EXIT=0 (3m30s) — **32.8MB**
- aapt2: `com.novafiles.app` vc=41 vn=0.41.0 minSdk=26 targetSdk=35
- APK: `download/NovaFiles-v0.41.0-debug.apk` — SHA-256 `347e432f…5293f7`

## 5. الميزات المنفذة فعلياً من Nova Intelligence
محرك نوايا حتمي (6 أوامر مرجعية مختبرة حرفياً) + قائمة أدوات مسموحة بلا أي أداة تدميرية + مدقق مسارات بختبارات سلبية حقيقية (رابط رمزي مبني أثناء الاختبار) + معاينة/تأكيد للكتابة + تنفيذ قراءة حقيقي فوق الفهرس والمحلل والمكررات + قواعد أتمتة تبدأ تجربة جافة + شرح أخطاء + سجل اختياري آمن. **ما زال مخططاً**: نموذج لغوي محلي (طبقة معيارية — المرحلة C)، اقتراحات تنظيم أعمق (B).

## 6. مخاطر الأمان المتبقية
1. كلمات مرور الخوادم نصاً صريحاً في DataStore (محجوبة من النسخ — التشفير P1)
2. لا اختبار أجهزة فعلي في هذه البيئة (سلوك النسخ الاحتياطي 12+ على جهاز حقيقي)
3. ترجمات v0.41 الجديدة مسودة أصلية — تحتاج مراجعة أصيلة قبل v1.0 (سجل ReleaseGate)
4. Release الموقّع يتطلب keystore المالك

## 7. التبعيات والتراخيص الجديدة
**صفر تبعيات جديدة** — المساعد مبني كاملاً على Kotlin القياسي والمحركات القائمة (قرار معماري: بلا أي اعتماد ML قبل بوابات المرحلة C). لم تُرفع إصدارات قائمة.

## 8. مسارات الملفات الناتجة الدقيقة
- APK: `/home/z/my-project/download/NovaFiles-v0.41.0-debug.apk`
- تقارير (داخل المستودع + منسوخة للتنزيل): `AUDIT_REPORT_AR.md`, `SECURITY_AUDIT_AR.md`, `FEATURE_MATRIX_AR.md`, `DEVELOPMENT_ROADMAP_AR.md`, `TEST_RESULTS_AR.md`, `FINAL_REPORT_AR.md`, `LOCAL_INTELLIGENCE_PLAN_AR.md`
- سجلات البناء: `/home/z/my-project/test-v041.log`, `build-v041.log`, `baseline-test.log`
- Git: فرع `nova-v0.41.0-upgrade` في `/home/z/my-project/NovaFiles` (مرفوع إلى GitHub)

## 9. الخطوة التالية ذات الأولوية
**P1: تشفير ftp_pass/dav_pass داخل DataStore عبر CredentialVault الحالي** (نمط موجودة، يستلزم ترحيل قراءة قديمة→جديدة + اختبار ترحيل)، ثم `gradle lint` فعلي وبناء Release فور توفير keystore.
