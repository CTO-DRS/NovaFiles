# NovaFiles — Comprehensive Product Roadmap to v1.0

> **Horizon:** October 2026 → October 2027 (12 months) · **Cadence:** one release every 6–8 weeks
> **Mission:** become the most capable, most private, most beautiful file manager on Android — with no rival in its class.
> **Model:** 100% free & open source (F-Droid + GitHub Releases + direct APK). No ads, no telemetry, ever.

NovaFiles already ships nine releases of real, tested engineering (86 JVM tests as of v0.11). This roadmap turns that foundation into a complete competitive package across the three pillars chosen for differentiation: **Unified Cloud**, **Delightful UX**, and **Uncompromising Privacy** — benchmarked against Solid Explorer, MiXplorer, X-plore, and Files by Google.

---

## 1. Where We Stand — v0.11.0 Snapshot (October 2026)

| Capability | Status |
|---|---|
| Browsing & ops | Full storage access, list/grid, thumbnails, multi-select, trash with restore |
| Instant search | Room-FTS4 background index, results before you finish typing |
| Vault | AES-256-GCM via Android Keystore, salted PIN + biometric |
| Archives | ZIP (read/write), 7z / TAR (gz/bz2/xz) / RAR4+RAR5 extraction, Zip-Slip guard |
| Network | FTP server (share via QR), FTP/SFTP/SMB2-3 clients, unified RemoteFs UI |
| Tablet | Dual-pane ≥ 600dp (iPad-style), one-tap send between panes, side directory tree |
| i18n | Arabic (RTL-first) + English, live switching, Material You |
| Quality | 86 JVM tests, CI on every push, tagged releases |

**Track record — consistently ahead of the original plan:**

| Shipped | What landed early | Originally planned for |
|---|---|---|
| v0.4.0 | Room-FTS instant indexing + hybrid live search | v0.2 |
| v0.5.0 | Embedded FTP server (zero extra dependencies) | v0.5 |
| v0.6.0 | FTP + SMB2/3 clients behind one RemoteFs UI, saved connections, 10 integration tests | v0.4 |
| v0.7.0 | Full ZIP: in-app viewer, multi-select compression, live progress, Zip-Slip protection | v0.6 |
| v0.8.0 | 7z / TAR / RAR4 via unified ArchiveEngine + shared slip guard | v0.6 |
| v0.9.0 | SFTP client (sshj): password + keyboard-interactive, TOFU host keys, MINA sshd test rig | v0.6 |
| v0.10.0 | Dual-pane tablet layout, pane-to-pane send, self-target copy guard | v0.6 |
| v0.11.0 | QR device-to-device transfer (ZXing), RAR5 via junrar 8.1.2, slide-in directory tree | v0.5/v0.6 |

---

## 2. Competitive Landscape

Where we stand today against the four benchmark competitors, and what v1.0 changes. Scoring: ● strong · ◐ partial · ○ missing.

| Capability | NovaFiles v0.11 | Solid Explorer | MiXplorer | X-plore | Files by Google | **NovaFiles v1.0 target** |
|---|---|---|---|---|---|---|
| Open source, no ads/telemetry | ● | ○ (paid) | ○ (closed) | ○ (paid) | ○ (closed) | ● |
| Instant indexed search | ● | ○ | ○ | ○ | ◐ | ● |
| Dual-pane (tablet) | ● | ● | ◐ | ● | ○ | ● |
| Archives: ZIP/7z/TAR/RAR4+5 | ● | ● | ● | ● | ○ | ● |
| FTP server + QR share | ● | ◐ | ● | ○ | ○ | ● |
| SMB / SFTP / FTP clients | ● | ● | ● | ● | ○ | ● |
| WebDAV / Nextcloud | ○ | ● | ● | ● | ○ | ● (v0.13) |
| Drive / Dropbox / OneDrive | ○ | ● | ● | ● | ◐ | ● (v0.16) |
| Storage analyzer + duplicates | ○ | ◐ | ● | ● | ● | ● (v0.14) |
| Batch rename engine | ○ | ● | ● | ● | ○ | ● (v0.12) |
| Wi-Fi Direct transfer | ○ | ◐ | ● | ◐ | ○ | ● (v0.12) |
| Encrypted vault + decoy mode | ◐ (no decoy) | ◐ | ◐ | ◐ | ○ | ● (v0.15) |
| Home-screen widgets | ○ | ● | ● | ● | ◐ | ● (v0.17) |
| RTL-first Arabic UX | ● | ◐ | ◐ | ◐ | ◐ | ● |

**Reading of the table:** the incumbents win today on breadth (clouds, analyzer, rename, widgets). No single competitor combines open source, privacy tooling, and network transfer the way v1.0 will — and none of them is RTL-first.

---

## 3. Strategy — Three Pillars

1. **Unified Cloud.** One browsing experience for local, LAN, and cloud. Extend the proven RemoteFs abstraction to WebDAV first (open-protocol, F-Droid-friendly), then OAuth providers (Drive/Dropbox/OneDrive) behind the same interface, with a single resumable transfer manager.
2. **Delightful UX.** iPad-grade tablet layouts already exist; next comes storage intelligence (visual analyzer, duplicate finder), power tools (batch rename, Wi-Fi Direct), widgets and gestures — polished to Files-by-Google simplicity without losing pro features.
3. **Uncompromising Privacy.** Vault 2.0 (folder-level encryption, decoy mode, recovery), secure wipe, zero network permissions beyond what a feature needs, reproducible builds — privacy as architecture, not a marketing checkbox.

---

## 4. Release Plan — v0.12.0 → v1.0.0

### v0.12.0 — Power User Tools (Nov–Dec 2026)

**Goal:** win the two most-requested pro features the big managers have and we don't: batch rename and router-free transfer.

- **Batch rename engine:** pattern builder (prefix/suffix/counter/padding/date), EXIF photo dates, case change, find & replace with regex, live preview table, full undo.
- **Wi-Fi Direct transfer:** peer discovery + pairing without any router; sender hosts the existing embedded FTP core over the P2P link; QR still does the handshake.
- **Background operation queue:** copy/move/extract continue off-screen with progress notification, pause/resume/cancel, conflict policy (skip/overwrite/rename) applied queue-wide.

| Field | Detail |
|---|---|
| Technical goals | Rename engine as pure Kotlin `RenameEngine` (JVM-testable); Wi-Fi Direct module isolating Android-specific APIs behind an interface; ops queue on WorkManager with foreground service |
| Effort | ~6 dev-weeks |
| Risks | Wi-Fi Direct OEM fragmentation → fallback path: auto-created hotspot + same FTP core; EXIF variance across camera vendors → androidx ExifInterface + fuzz fixtures |
| Success criteria | Rename 500 files < 3 s incl. preview; Direct pairing < 15 s on 3 device pairs; ≥ 100 JVM tests green |

### v0.13.0 — Unified Cloud I: WebDAV + Transfer Foundation (Jan–Feb 2027)

**Goal:** open-protocol cloud with the same snappiness as local browsing, and a transfer engine built to carry everything that follows.

- **WebDAV client** (Nextcloud, ownCloud, generic servers) over OkHttp; chunked PROPFIND listing, `If-Match` conflict detection.
- **StorageProvider abstraction:** RemoteFs, local storage, and WebDAV behind one interface — connection management, browsing, ops, and search metadata unified.
- **Transfer manager v2:** parallel worker pool, resumable chunked uploads/downloads, queue UI with per-item speed/pause/cancel, network-type constraints (Wi-Fi only by default).
- Credentials sealed in EncryptedSharedPreferences backed by Keystore; per-connection bandwidth caps.

| Field | Detail |
|---|---|
| Technical goals | `StorageProvider` SDK-style interface documented; directory listing cache with ETag revalidation; embedded test server covering WebDAV verbs |
| Effort | ~7 dev-weeks |
| Risks | WebDAV server incompatibility (Apache/Nginx/IIS/Nextcloud variants) → interop matrix suite run in CI; path/encoding edge cases (spaces, Arabic, emoji names) → property-based tests |
| Success criteria | 10k-entry dir browses < 1.5 s warm; interop matrix ≥ 95% pass vs Nextcloud 28–30; ≥ 115 JVM tests |

### v0.14.0 — Storage Intelligence (Mar–Apr 2027)

**Goal:** beat Files by Google at its own game — clean, visual, automated storage care — while staying offline-first.

- **Storage analyzer:** interactive treemap + category breakdown (video/audio/images/docs/apps/other), drill-down navigation, "what's eating my phone" first screen.
- **Duplicate finder:** size+partial-hash prefilter, full MD5 confirmation, optional perceptual image matching (dHash) for near-duplicates, grouped results with safe batch actions (always preview-before-delete).
- **Junk cleaner:** empty dirs, orphan APKs, stale thumbnails, leftover downloads — each category individually reviewable, nothing auto-deleted silently.
- **Large & old files dashboard** fed by the existing FTS indexer (zero new scanning passes).

| Field | Detail |
|---|---|
| Technical goals | Analyzer on incremental indexer snapshots (no second walk); hashing off-main-thread with WorkManager + battery constraints; treemap in custom Compose Canvas |
| Effort | ~6 dev-weeks |
| Risks | Full-scan battery drain → constrained background jobs + incremental diffs; fuzzy-duplicate false positives → conservative thresholds + mandatory preview |
| Success criteria | 100k files analyzed < 30 s in background; 10k images dup-scanned < 60 s; zero deletions without explicit confirmation; ≥ 130 JVM tests |

### v0.15.0 — Vault 2.0 & Privacy Hardening (May–Jun 2027)

**Goal:** privacy tooling no mainstream manager offers — pro-grade encryption UX that a normal person can survive losing a phone with.

- **Folder-level encryption:** per-folder keys via HKDF under the Keystore root; tree structure preserved; streaming chunked cipher for large content.
- **Decoy mode:** calculator disguise launcher + secret PIN sequence; indistinguishable real/calculator behavior until unlocked.
- **Auto-hide & intrusion log:** vault hides 30 s after leaving the app; optional failed-attempt photo + timestamp log (all stored locally, encrypted).
- **Recovery:** encrypted vault-index backup export/import with a 12-word recovery phrase — key loss is no longer data loss.
- **Vault on SD card** via SAF; **secure wipe** (multi-pass overwrite) for leaving Vault/local sensitive files; biometric re-auth gate for destructive ops.

| Field | Detail |
|---|---|
| Technical goals | Crypto design doc reviewed against OWASP MASVS; recovery flow tested by scripted drills; all vault IO streaming (constant memory) |
| Effort | ~6 dev-weeks |
| Risks | Key loss = catastrophic → mandatory recovery-phrase setup before first use; performance on big folders → chunked AES-GCM with per-chunk auth tags |
| Success criteria | Encrypt 1 GB < 60 s on mid-range device; recovery drill passes from cold restore; decoy mode passes behavioral diff review; ≥ 145 JVM tests |

### v0.16.0 — Unified Cloud II: OAuth Providers + Camera Sync (Jul–Aug 2027)

**Goal:** the three consumer clouds behind the exact same UI as local folders — plus the killer sync feature that retains users daily.

- **Google Drive + Dropbox + OneDrive** via OAuth 2.0 PKCE (AppAuth); token refresh, scope minimization, account switching.
- **Camera sync (one-way):** new photos/videos upload to a chosen cloud folder like Google Photos; constraints: Wi-Fi + charging by default; exact-once tracking via content hash ledger.
- **Federated transfers:** copy Drive → SMB or OneDrive → Vault directly through the transfer manager (streamed, not device-staged where the provider allows).
- Unified transfer manager UI absorbs cloud, LAN, and local queues.

| Field | Detail |
|---|---|
| Technical goals | Provider adapter = ~1 interface implementation + capability flags (no per-provider UI); rate-limit handling with exponential backoff + jitter; sync ledger in Room |
| Effort | ~8 dev-weeks |
| Risks | Google Drive OAuth verification lead time (security review can take weeks) → start verification during v0.15 window, ship in test-mode until approved; provider API quirks → adapter-level contract tests against recorded fixtures |
| Success criteria | All 3 OAuth flows pass on-device; 2 GB resumable upload survives process death; camera sync battery < 2%/day; ≥ 160 JVM tests |

### v0.17.0 — Experience Polish & Performance (Sep 2027)

**Goal:** make everything built so far feel instant, personal, and accessible — the release where the app stops being "powerful" and becomes "lovable".

- **Widgets (Glance):** storage shortcut, recent files, vault toggle, transfer status.
- **Gestures:** swipe actions per list item (configurable), drag & drop across panes, double-tap-to-navigate.
- **Theming:** extended Material You palettes, per-folder accent, optional icon-pack support.
- **File associations:** register as handler for text/image/audio/archive types; "open with NovaFiles" everywhere.
- **Editor 2.0:** markdown preview, syntax highlighting, large-file virtualized mode.
- **Accessibility:** complete TalkBack coverage, dynamic type across all screens, contrast audit.
- **Performance:** R8 full mode, Baseline Profiles, cold start < 300 ms P50, APK < 30 MB.

| Field | Detail |
|---|---|
| Technical goals | Startup instrumentation in CI (macrobenchmark); jank budget < 1% dropped frames on scroll; all new surfaces RTL-reviewed first |
| Effort | ~6 dev-weeks |
| Risks | Widget RemoteViews vs Compose gap → Glance with conservative layouts; baseline-profile gains vary on OEM ROMs → profile installer fallback verified |
| Success criteria | Cold start < 300 ms P50 on test rig; TalkBack audit zero critical issues; jank < 1%; ≥ 175 JVM tests |

### v1.0.0 — Public Launch (Oct 2027)

**Goal:** the version we put our name on: hardened, translated, reproducible, and available in every open channel.

- **Stability hardening:** fuzzing of FileOps/archive/network paths, 24 h soak tests, beta-channel crash-free rate ≥ 99.5%.
- **Localization:** 10 community languages via translation platform; RTL audit across every screen.
- **Distribution:** F-Droid package (reproducible builds, vendored/clean dependencies), GitHub Releases CI producing signed APKs, Play Store closed→open testing track.
- **Docs:** user guide site, FAQ, CONTRIBUTING.md, screenshot set for store listings.

| Field | Detail |
|---|---|
| Technical goals | Bit-identical reproducible build verified independently; signing + metadata pipeline fully scripted |
| Effort | ~6 dev-weeks |
| Risks | F-Droid build environment strictness → 3–4 week buffer, dependency audit early; Play policy review friction → open-source declarations prepared |
| Success criteria | F-Droid live; reproducible build confirmed by 2 independent parties; 10 languages ≥ 95% translated; crash-free ≥ 99.5% in beta; ≥ 200 JVM tests |

---

## 5. Timeline Overview

| Quarter | Release | Theme | Pillar focus |
|---|---|---|---|
| Q4 2026 | v0.12.0 | Power tools (rename, Wi-Fi Direct, background queue) | UX |
| Q1 2027 | v0.13.0 | WebDAV + StorageProvider + transfer manager v2 | Cloud |
| Q1–Q2 2027 | v0.14.0 | Storage analyzer, duplicates, junk cleaner | UX |
| Q2 2027 | v0.15.0 | Vault 2.0, decoy mode, recovery, secure wipe | Privacy |
| Q3 2027 | v0.16.0 | Drive/Dropbox/OneDrive + camera sync | Cloud |
| Q3 2027 | v0.17.0 | Widgets, gestures, a11y, performance | UX |
| Q4 2027 | v1.0.0 | Hardening, 10 languages, F-Droid + Play launch | All |

---

## 6. Distribution & Open-Source Strategy

- **Channels:** GitHub Releases (signed APK per tag, CI-built), F-Droid (reproducible builds), direct APK from the README — Play Store as an additional convenience, not a dependency.
- **Trust posture:** no ads, no analytics, no tracking; network permission justified per feature; dependency count kept minimal and auditable; every release tagged and documented.
- **Community:** issue templates, roadmap discussions public, "help wanted" labels for community contributions, translation platform open from v0.17.

---

## 7. Continuous Priorities (Every Release)

| Priority | Rule |
|---|---|
| Security | Least-privilege permissions; security review for every new network/crypto surface; MASVS checklist on v0.15 and v1.0 |
| Performance | Lazy lists everywhere, zero main-thread IO, thumbnail + index caching, per-release startup budget |
| Arabic & RTL | Every feature RTL-tested first; strings complete in ar + en before merge |
| Quality | All JVM tests green + ktlint clean before tag; integration test for every new protocol/format |
| Transparency | README + ARCHITECTURE updated in the same commit as the feature; every release documented |

---

## 8. Beyond v1.0 (Outlook, Not Committed)

On-device AI organization (auto-categorized galleries, semantic search over OCR'd text), cloud-to-cloud sync rules, plugin system for community file handlers, and a desktop companion sharing the same transfer protocol. These graduate into a v1.x roadmap once v1.0 ships and user feedback sets the order.

---

## 9. Track Record Governance

- Each release must be **stable before the next starts** (the original project rule, unchanged).
- Effort estimates are for the solo maintainer at current pace (~6 weeks release); scope cuts hit features bottom-up, never quality gates.
- This document is updated in the same commit as every release; deviations are recorded in the track-record table with reasons.

---

<div dir="rtl">

## 10. الملخص التنفيذي (بالعربية)

### الوضع الحالي — أكتوبر 2026

أنجز NovaFiles حتى الإصدار **v0.11.0** تسعة إصدارات فعلية قبل الموعد المخطط، بميزات تنافسية كاملة: بحث فوري مفهرس، خزنة مشفرة AES-256، أرشيفات ZIP/7z/TAR/RAR4+RAR5، خادم FTP مع مشاركة QR، عملاء FTP/SFTP/SMB، وضع لوحتي بنمط iPad مع شجرة مجلدات جانبية، وواجهة عربية RTL أولاً — بدعم **86 اختبار جافا** وبدون أي إعلانات أو تتبع.

### خارطة الـ 12 شهراً القادمة (سبعة إصدارات حتى v1.0)

| الإصدار | التوقيت | الثيمة | الميزة المميزة |
|---|---|---|---|
| **v0.12.0** | نوفمبر–ديسمبر 2026 | أدوات المحترفين | إعادة تسمية جماعية بنمط ذكي + نقل Wi-Fi Direct بلا راوتر + طابور عمليات خلفي |
| **v0.13.0** | يناير–فبراير 2027 | السحابة ١ | عميل WebDAV (Nextcloud) + طبقة StorageProvider موحدة + مدير نقل قابل للاستئناف |
| **v0.14.0** | مارس–أبريل 2027 | ذكاء التخزين | محلل تخزين مرئي (Treemap) + كشف التكرارات + منظف الملفات — منافسة Files by Google |
| **v0.15.0** | مايو–يونيو 2027 | الخصوصية | الخزنة 2.0: تشفير مجلدات كامل + وضع التنكر (حاسبة) + استرجاع بكلمة أنقاذ + مسح آمن |
| **v0.16.0** | يوليو–أغسطس 2027 | السحابة ٢ | Google Drive وDropbox وOneDrive عبر OAuth + مزامنة الكاميرا التلقائية |
| **v0.17.0** | سبتمبر 2027 | الصقل | ودجات سطح المكتب + إيماءات + ثيمات + إتاحة كاملة + بدء بارد أقل من 300ms |
| **v1.0.0** | أكتوبر 2027 | الإطلاق | تثبيت الاستقرار + 10 لغات + نشر على F-Droid وPlay Store + بناء قابل لإعادة الإنتاج |

### لماذا هذه الخارطة تحقق اللامنافسة؟

المقارنة الشاملة أعلاه (القسم 2) تُظهر أن المنافسين الأربعة — Solid Explorer وMiXplorer وX-plore وFiles by Google — يتفوقون اليوم على انتشار الميزات، لكن **لا أحد منهم يجمع**: مفتوح المصدر بلا إعلانات + بحث فوري مفهرس + خزنة بوضع تنكر + نقل QR/Wi-Fi Direct + دعم عربي RTL أصيل. بنهاية 2027 يملأ NovaFiles كل فجوة متبقية (سحابة OAuth، محلل تخزين، ودجات، إعادة تسمية جماعية) مع الحفاظ على نقاط تفوقه الحالية، فيصبح الخيار الوحيد الذي يقدم كل شيء مجاناً وبخصوصية كاملة.

### الركائز الثلاث ومبادئ الحوكمة

التركيز ينصبّ على ثلاث ركائز: **السحابة الموحدة** (بروتوكول مفتوح أولاً ثم OAuth خلف واجهة واحدة)، **تجربة الاستخدام** (ذكاء تخزين + أدوات محترفين + صقل)، و**الخصوصية بلا تنازلات** (تشفير معماري لا شعارات تسويقية). القاعدة الحاكمة كما هي: كل إصدار مستقر قبل بدء التالي، كل ميزة تُختبر RTL أولاً، ولا يُختم وسم قبل نجاح كل الاختبارات وتحديث التوثيق في نفس الإيداع.

</div>
