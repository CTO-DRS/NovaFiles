<div dir="rtl">

# 🏗️ معمارية NovaFiles

نمط **MVVM + Clean-lite**: طبقات واضحة بلا مبالغة هندسية، Dependency Injection يدوي بسيط، وكل شيء Compose-first.

<div dir="ltr">

```
┌─────────────────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                             │
│  Screens + ViewModels ── حالة كأنماط StateFlow/State    │
├─────────────────────────────────────────────────────────┤
│  Core Layer (Kotlin نقي + Android SDK)                  │
│  FileOps · LocalBrowser · SearchEngine · VaultManager   │
├─────────────────────────────────────────────────────────┤
│  Data Layer                                             │
│  Room (مفضلة/وسوم) · DataStore (إعدادات) · Clipboard    │
├─────────────────────────────────────────────────────────┤
│  Android System                                         │
│  Storage (File I/O) · Keystore · Biometric · DataStore  │
└─────────────────────────────────────────────────────────┘
```

</div>

---

## 1. نقطة الدخول والتجميع

| المكوّن | الملف | دوره |
|---------|-------|------|
| `NovaApp` | `NovaApp.kt` | Application class — يبني `AppContainer` |
| `AppContainer` | `NovaApp.kt` | Manual DI: قاعدة البيانات + الإعدادات + الخزنة + الحافظة (lazy) |
| `MainActivity` | `MainActivity.kt` | AppCompatActivity واحد يستضيف كامل واجهة Compose |
| `NovaRoot` | `ui/nav/NovaNavHost.kt` | Scaffold رئيسي + شريط تنقل سفلي + NavHost |

> لماذا لا Hilt؟ للمساهمة السريعة: صفر معالجات تعليقات، صفر KAPT/KSP إضافي، وسلسلة تبعيات واضحة تُقرأ في ملف واحد. الترقية لـ Hilt متاحة لاحقاً دون تغيير الواجهات.

## 2. طبقة النواة `core/`

### `core/fs/FileOps.kt` — محرك العمليات
كل عمليات الإدخال/الإخراج في كائن واحد: `copyAll` (تكراري مع تتبع بايتات)، `moveAll` (مسار سريع عبر `renameTo` ثم نسخ احتياطي)، `moveToTrash` (طابع زمني يمنع تعارض الأسماء)، `rename`، `newFolder`. جميعها `suspend` تعمل على `Dispatchers.IO` وتعيد `Result<T>` — الواجهة لا تحجب أبداً.

### `core/fs/LocalBrowser.kt` — قارئ المجلدات
سرد وترتيب موحّد (مجلدات أولاً دائماً) + تنسيق الحجم/التاريخ بأدوات مشتركة تستهلكها كل الشاشات.

### `core/fs/SearchEngine.kt` — البحث الذكي
بث `Flow<SearchState>` (Scanning/Results) ينبثق نتائج تدريجياً أثناء الفحص، مع `ensureActive()` في كل حلقة لإلغاء فوري عند تعديل الاستعلام. المرشحات (نوع/حجم/عمر) تُطبق أثناء المسح لا بعده — لا هدر ذاكرة.
> v0.2 سيستبدل الفحص الحي بفهرسة Room-FTS (انظر ROADMAP) دون تغيير واجهة الشاشة.

### `core/fs/FileType.kt` — التصنيف
خريطة امتدادات → تصنيف (صور/فيديو/...) تخدم الأيقونات وألوانها **و**مرشحات البحث من مصدر حقيقة واحد.

### `core/vault/VaultManager.kt` — التشفير
- مفتاح AES-256 يولَّد داخل **Android Keystore** بلا تصدير (مدمج بالعتاد حيث يُتاح)
- صيغة الملف `.nvf`: `NVF1` + IV عشوائي 12B + GCM ciphertext (مصادقة مدمجة ترفض العبث)
- PIN: ملح عشوائي 16B + SHA-256 في DataStore — لا يُخزن أي شيء نصياً
- فك التشفير يمر عبر BiometricPrompt أو PIN — القرار واجهة فقط، النواة لا تفرق

## 3. طبقة البيانات `data/`

| الملف | الوصف |
|-------|-------|
| `data/db/NovaDb.kt` | Room: جدولان — `favorites` و`tags`، DAOs تُبَث `Flow` للتفاعل الفوري |
| `data/prefs/SettingsStore.kt` | DataStore Preferences: العرض/الترتيب/المخفيّة/السمة + وصول خام للخزنة |
| `ClipboardState` (نفس الملف) | حافظة النسخ/النقل كحالة `mutableStateOf` قابلة للمراقبة في Compose |

## 4. طبقة الواجهة `ui/`

كل شاشة = ملف واحد يحوي الـ Composables + ViewModel منفصل عند الحاجة:

```
ui/
├── theme/        ألوان الهوية + NovaTheme (ديناميكي API 31+ مع fallback)
├── nav/          المسارات (Routes) + شريط التنقل + فتح الملفات حسب النوع
├── browser/      الشاشة الأم: ViewModel ضخم بحالة كاملة + عناصر قائمة/شبكة + حوارات
├── search/       بحث فوري مع شريط مرشحات أفقي قابل للتمرير
├── favorites/    قائمة Room مباشرة مع حذف سحب/زر
├── vault/        ثلاث حالات: إنشاء PIN → قفل → محتوى الخزنة (نتيجة ActivityResult)
├── editor/       محرر نصوص بسيط مع حفظ على IO
├── viewer/       عارض صور بتكبير pointerInput + graphicsLayer
└── settings/     لغة (AppCompatDelegate) + سمة (SegmentedButton) + تفريغ السلة
```

**قواعد ثابتة**:
- الحالة تنزل (StateFlow/State)، الأحداث تصعد (لambdas) — لا مصدر حقيقة مزدوج
- كل نص UI عبر `stringResource` — لا نصوص صلبة (لغتان من ملفي strings)
- الكوروتينات لا تتجاوز ViewModel/rememberCoroutineScope أبداً

## 5. التدويل وRTL

- `values/strings.xml` عربي (افتراضي) + `values-en/` إنجليزي
- `locales_config.xml` + خدمة `AppLocalesMetadataHolderService` مع `autoStoreLocales` — التبديل الفوري عبر `AppCompatDelegate.setApplicationLocales` يعمل من API 26 (بدعم AppCompat) حتى 35 (per-app language أصلي)
- `android:supportsRtl="true"` — كل التخطيطات آلية الاتجاه (لا استخدام لأرقام اتجاه ثابتة)

## 6. الأمان — قرارات مصممة مسبقاً

| القرار | السبب |
|--------|-------|
| مفتاح التشفير في Keystore فقط | لا يمكن استخراجه حتى مع صلاحيات root (حيث StrongBox متاح) |
| GCM بدل CBC | مصادقة مدمجة — أي تعديل على بايت واحد يفشل الفك بصمت آمن |
| PIN مملح + مجزأ | لا PIN خام في أي مخزن |
| FileProvider لكل المشاركة | لا نكشف `file://` أبدًا (ممنوع منذ API 24) |
| حذف بعد تشفير | الأصل يُحذف فور نجاح التشفير (تحسين: مسح آمن multi-pass في v0.3) |

## 7. الاعتماديات (كلها أساسية، لا فخامة)

| المكتبة | الغرض |
|---------|-------|
| Jetpack Compose (BOM) + Material 3 + icons-extended | الواجهة كاملة |
| Navigation Compose | التنقل بين 7 شاشات |
| Room + KSP | المفضلة والوسوم (وFTS في v0.2) |
| DataStore Preferences | الإعدادات — بديل SharedPreferences الحديث |
| Coil | صور مصغرة فورية بذاكرة مخبأة |
| AndroidX Biometric | بصمة/وجه للخزنة |
| kotlinx-coroutines | كل العمليات الخلفية |

> لا Firebase، لا Analytics، لا SDK تتبع — خصوصية كاملة بالتصميم.

</div>
