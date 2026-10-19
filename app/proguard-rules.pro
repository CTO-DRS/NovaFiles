# NovaFiles — v0.17: R8 full mode للإصدار النهائي (APK < 30MB + بدء أسرع)
# الطبقات التي تُ reached عبر انعكاس أو JNI يجب إبقاؤها صراحةً.

# ---------- Kotlin / Coroutines ----------
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.coroutines.SafeContinuation { private volatile java.lang.Object result; }

# ---------- Room (v0.4+) ----------
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# ---------- OkHttp / Okio (v0.13) ----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn org.codehaus.mojo.animal_sniffer.*

# ---------- sshj / MINA (v0.9 SFTP) ----------
-dontwarn com.hierynomus.sshj.**
-dontwarn net.schmizz.sshj.**
-dontwarn org.apache.sshd.**
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.** { *; }
-dontwarn org.slf4j.impl.**
# مكتبة mbassy داخل sshj تستخدم EL اختيارياً (غير موجودة على أندرويد — لا تُستدعى)
-dontwarn javax.el.**
-dontwarn net.engio.mbassy.**
# eddsa يشير إلى فئات sun الداخلية (بديل التوقيع على JVM فقط)
-dontwarn sun.security.x509.**
-dontwarn net.i2p.crypto.eddsa.**

# ---------- SMBJ (v0.6 SMB) ----------
-keep class com.hierynomus.smbj.** { *; }
-keep class com.hierynomus.mssmb2.** { *; }
-keep class com.hierynomus.protocol.** { *; }
-dontwarn com.hierynomus.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

# ---------- Apache Commons (compress/net) ----------
-dontwarn org.apache.commons.compress.**
-dontwarn org.apache.commons.net.**
-keep class org.apache.commons.compress.** { *; }

# ---------- junrar (v0.11 RAR5) ----------
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**

# ---------- ZXing (v0.11 QR) ----------
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**

# ---------- Tukaani xz ----------
-dontwarn org.tukaani.xz.**

# ---------- JAXB / reflection المستخدمة في المحللات ----------
-dontwarn java.beans.**
-dontwarn javax.naming.**

# ---------- قواعد R8 full mode العامة ----------
-repackageclasses 'nova'
-allowaccessmodification
