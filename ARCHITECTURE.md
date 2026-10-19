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

### `core/fs/DirTree.kt` — منطق شجرة المجلدات (v0.11)
منطق خالص بلا Android: `build()` يحوّل جذوراً + مجموعة توسعة إلى قائمة `TreeNode` مسطّحة (اسم/عمق/مفتوح/حالي/سلف) لعرضها في LazyColumn، و`defaultExpanded()` يشتق سلسلة أسلاف المسار الحالي للتوسعة الأولى. مزوّد الأبناء `listDirs` قابل للحقن للاختبار — 9 اختبارات JVM.

### `core/qr/QrCode.kt` — نقل QR (v0.11)
طبقتان منفصلتان: `QrPayloads` — دوال نقية تبني روابط `ftp://user:pass@ip:port[/path]` بترميز RFC 3986 (المستخدم/كلمة المرور والمسار مقطعاً مقطعاً لتبقى الفواصل)، و`QrCode` — توليد `BitMatrix` عبر ZXing core و`Bitmap` جاهزة للعرض. 8 اختبارات JVM تشمل فك الرمز عكسياً.

### `core/ftp/FtpServer.kt` — خادم FTP خالص + FtpServerService
خادم FTP بلغة Kotlin خالصة (RFC 959: PASV/PORT/REST، مقصور على الجذر ضد `../`) — تستضيفه خدمة أمامية، وتغذّي عليه ميزات «مشاركة WiFi» و«إرسال QR» (v0.11) و«النقل المباشر Wi-Fi Direct» (v0.12) حيث يُشغَّل بجذر مجلد العنصر المرسل أو بجذر التخزين عبر واجهة P2P.

### `core/rename/RenameEngine.kt` — إعادة التسمية الجماعية (v0.12)
Kotlin خالص بلا Android: خط أنابيب (فصل امتداد ← بحث/استبدال حرفي أو Regex ← تحويل حالة ← اسم أساس + عدّاد محشو ← تاريخ ← بادئة/لاحقة) ثم كشف تعارضات (موجود على القرص / مكرر داخل الدفعة / غير صالح). التنفيذ على **مرحلتين عبر أسماء مؤقتة** يضمن سلامة سلاسل التبديل مع تراجع كامل عند أي فشل، و`undo()` يعيد الأسماء الأصلية. التواريخ قابلة للتجاوز (تواريخ EXIF للصور من الطبقة الأعلى) — 24 اختبار JVM.

### `core/ops/OpQueue.kt` — قائمة العمليات الخلفية (v0.12)
عامل واحد متسلسل يستهلك قناة FIFO: COPY/MOVE/TRASH/ZIP/EXTRACT بتقدّق بايتي (حتى الأرشيفات عبر ردود نداء ZipArchive/CommonArchive)، إيقاف/استئناف ببوابات تفتيش في حلقة النسخ، إلغاء تعاوني، وسياسات تعارض للدفعة (إعادة تسمية/تخطي/استبدال). تُغذّى تحديثاتها إلى `OpNotifier` (إشعار أندرويد) و`OpService` (خدمة أمامية dataSync ترفع أولوية العملية وتتوقف عند فراغ القائمة). النواة كلها JVM — 16 اختباراً حتمياً عبر جدوّل زمني افتراضي.

### `core/wifip2p/DirectCore.kt` — نواة Wi-Fi Direct (v0.12)
نقي بالكامل: `DirectPayloadCodec` يرمّز مصافحة `nova-direct://host:port?name=&files=&user=&pass=` بترميز URL يدعم العربية يدوياً (بلا java.net)، و`DirectStateReducer` يحوّل أحداث P2P (جيران/اتصال/انفصال/فشل) إلى حالة واجهة واحدة. البروتوكول المعتمد: المرسل يطلب ملكية المجموعة (ownerIntent=15) ويستضيف خادم FTP المدمج على 192.168.49.1، والمستقبل يتصل كعضو ويعرف عنوان المالك دائماً. الغلاف الرقيق `WifiDirectManager` يحتوي استدعاءات WifiP2pManager فقط — 9 اختبارات JVM.

### `core/network/WebDavFs.kt` + `DavParser.kt` — عميل WebDAV (v0.13)
`WebDavFs` يطبّق `RemoteFs` فوق OkHttp (PROPFIND Depth 0/1، GET مع Range، PUT مع If-Match/If-None-Match، MKCOL، DELETE متسامح مع 404، MOVE/COPY مع Destination مطلق وOverwrite: F) بمصادقة Basic استباقية UTF-8. ترميز المسارات مقطعاً مقطعاً RFC 3986، والقدرات الخاصة مكشوفة: `listDav` (مع ETag المجموعة) و`statDav` و`readRange` (للاستئناف) و`copyServerSide/moveServerSide`. المحلل `DavParser` SAX خالص بلا اعتماديات: يطابق الأسماء المحلية فقط (يتسامح مع بادئات المساحات)، يفك href المطلق والمرمز، يجرب RFC 1123 ثم ISO 8601، ويمنع DOCTYPE (XXE). الاستثناءات متعاقد عليها: `DavConflictException` (412) و`DavUnauthorizedException` (401) — الطبقة العليا تطبق سياساتها عليهما.

### `core/provider/` — تجريد StorageProvider (v0.13)
`StorageProvider` واجهة SDK موثقة: معرف + مخطط + displayUrl + **قدرات معلنة** (`ProviderCapability`) + عمليات موحدة (list/stat/readTo/writeFrom/mkdir/delete/rename/move/copy) وقراءة مدى اختيارية `readRangeTo` — غير المدعوم يلغّم `UnsupportedCapabilityException` بدل الفشل الصامت. مهايئان: `LocalStorageProvider` (جذر محصّن بـ canonicalPath ضد الهروب، وفرز مجلدات-أولاً موحد) و`RemoteFsProvider` (يلف كل بروتوكولات RemoteFs ويكشف قدرات WebDavFs ديناميكياً). `DavListingCache` LRU (accessOrder) مع إعادة تحقق ETag: إصابة ذاكرة = طلب Depth:0 رخيص، والخوادم بلا ETag تثق بالنسخة حتى `invalidate`.

### `core/transfer/TransferManager.kt` — مدير النقل v2 (v0.13)
تجمّع عمال متوازٍ فوق `CoroutineScope` محقون (العدّ بالوظائف الحية لا بالحالة — الحالة تُضبط غير متزامنة داخل العامل). **جسر التعليق**: المصدر الحاجب يجري في كوروتين ابن يدفع الدلتا عبر قناة UNLIMITED (`trySend`) بينما يستنزفها العامل في سياقه المعلق — فتعمل بوابة الإيقاف وحد السماحية والسرعة و`ensureActive()` بين القطع بلا تجميد النسخ. الاستئناف: `ResumeJournal` يفصل ملف البيانات `.nfpart` عن سجل الحالة `.nfjournal` (حجم + ETag + مسار بترميز base64 — صفر تعارض فواصل مع العربية) وتحقق ثلاثي قبل الاستئناف. قيود الشبكة: فاحص محقون + قناة CONFLATED `netWake` لاستيقاظ حدثي بلا اقتراع. `RateLimiter` دلو رموز مع `delay` افتراضي الزمن. النواة كلها JVM — 18 اختباراً حتمياً.

### `core/security/CredentialVault.kt` — خزنة الاعتماد (v0.13)
واجهة `CredentialVault` (save/load/delete/ids) بتنفيذين: `AesGcmVault` — AES-256-GCM بـ IV عشوائي 12B لكل عملية وصيغة `NFCV1:iv:cipher` فوق مخزن نصي محقون، وفك فاشل يعيد `null` آمناً (بصمة GCM ترفض المفتاح الخاطئ والعبث) — و`InMemoryVault` للاختبارات. طبقة أندرويد `KeystoreVault` تولّد المفتاح داخل AndroidKeyStore (256-bit، GCM، بلا تصدير) وتكيّف SharedPreferences إلى `MutableMap`. بديل مباشر لمكتبة security-crypto المُهلكة — 8 اختبارات JVM.

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
├── browser/      الشاشة الأم: ViewModel ضخم بحالة كاملة + عناصر قائمة/شبكة + شجرة جانبية + حوارات
├── qr/           حوار QR بنمط iOS: بطاقة بيضاء + رابط قابل للنسخ
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
| Apache Commons Compress + XZ | قراءة 7z/TAR/GZ/BZ2/XZ |
| junrar 8.1.2 | قراءة واستخراج RAR4 **وRAR5** (v0.11) |
| ZXing core 3.5.3 | توليد رموز QR للمشاركة (v0.11) |
| Apache MINA sshd (اختبارات فقط) | خادم SSH مدمج لاختبار SFTP حقيقياً |

> لا Firebase، لا Analytics، لا SDK تتبع — خصوصية كاملة بالتصميم.

</div>
