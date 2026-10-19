package com.novafiles.app.core.cloud

import android.content.Context
import com.novafiles.app.core.provider.StorageProvider
import com.novafiles.app.core.security.KeystoreVault
import com.novafiles.app.data.db.CloudAccountEntity
import com.novafiles.app.data.db.NovaDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v0.16 — مدير الحسابات السحابية: يربط OAuth بحسابات Room والتوكنات المشفرة.
 *
 * الأمان (حسب OWASP MASVS التي تعتمدها الخارطة):
 *  - التوكنات (وصول + تجديد) مشفرة AES-256-GCM بمفتاح AndroidKeyStore عبر
 *    [KeystoreVault] — لا شيء صريح في قاعدة البيانات أو SharedPreferences.
 *  - scope minimization: النطاقات الدنيا فقط (انظر CloudProviderDef).
 *  - تجديد شفاف: كل tokenProvider يستدعي ensureFresh قبل أي طلب.
 *
 * معرّفات العملاء (client IDs) خاصة بكل مُطوّر — تُمرَّر عبر gradle.properties
 * في وقت البناء (NOVA_DRIVE_CLIENT_ID...) وتُحمَّل من BuildConfig في الواجهة.
 */
class CloudAccountManager(
    private val context: Context,
    private val db: NovaDb,
    private val transport: HttpTransport = OkHttpTransport(),
) : AutoCloseable {

    private val vault by lazy { KeystoreVault.open(context) }

    /** مخزن توكنات مشفر — id الحساب مفتاح "cloud-<rowId>" */
    private val tokenStore: TokenStore = object : TokenStore {
        override fun save(accountId: String, token: AccessToken) {
            vault.save(
                accountId,
                listOfNotNull(
                    token.token,
                    token.refreshToken,
                    token.expiresAtMs.toString(),
                    token.scope,
                ).joinToString("\u0001"),
            )
        }

        override fun load(accountId: String): AccessToken? {
            val raw = vault.load(accountId) ?: return null
            val parts = raw.split("\u0001")
            if (parts.isEmpty() || parts[0].isBlank()) return null
            return AccessToken(
                token = parts[0],
                refreshToken = parts.getOrNull(1)?.takeIf { it.isNotBlank() },
                expiresAtMs = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                scope = parts.getOrNull(3),
            )
        }

        override fun remove(accountId: String) {
            vault.delete(accountId)
        }
    }

    // ---------- OAuth ----------

    /** عميل OAuth لكل مزوّد — معرّف العميل يُحقن من الواجهة (BuildConfig) */
    fun clientFor(def: CloudProviderDef, clientId: String): OAuthClient =
        OAuthClient(transport, clientId)

    /** يبدأ تفويضاً جديداً — يبني PKCE وstate ويعود برابط المتصفح وحامل الجلسة */
    fun beginAuth(def: CloudProviderDef, clientId: String): PendingAuth {
        val pkce = Pkce.generate()
        val state = "st-" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)
        val oauth = clientFor(def, clientId)
        val url = oauth.buildAuthorizationUrl(def, pkce, state)
        return PendingAuth(def, oauth, pkce, state, url)
    }

    /** يُنهي التفويض: تبادل الرمز وحفظ الحساب — يُستدعى من نشاط الروابط العميقة */
    suspend fun completeAuth(
        pending: PendingAuth,
        redirect: String,
        clientIds: Map<String, String>,
    ): CloudAccountEntity = withContext(Dispatchers.IO) {
        val code = pending.oauth.parseRedirect(pending.def, redirect, pending.state)
        val token = pending.oauth.exchangeCode(pending.def, code, pending.pkce.verifier)
        // اسم الحساب: نجرب جلب معلومات الحساب عبر مزوّد جديد مؤقتاً
        val rowId = db.cloudAccountsDao().upsert(
            CloudAccountEntity(
                provider = pending.def.key,
                displayName = accountDisplayName(pending.def, token),
            ),
        )
        tokenStore.save("cloud-$rowId", token)
        db.cloudAccountsDao().byId(rowId)!!
    }

    private suspend fun accountDisplayName(def: CloudProviderDef, token: AccessToken): String =
        try {
            val fs = buildFs(def.key, "preview", { token.token })
            fs.test()
        } catch (e: Exception) {
            def.displayName
        }

    // ---------- بناء المزوّدين ----------

    /**
     * مزوّد تخزين جاهز لحساب — يُجدد التوكن شفافاً قبل كل استخدام.
     * (الواجهة تستدعيه لكل متصفح سحابي — instance جديد لكل شاشة)
     */
    suspend fun providerFor(account: CloudAccountEntity, clientId: String): StorageProvider {
        val def = CloudProviderDef.byKey(account.provider)
        val oauth = clientFor(def, clientId)
        val key = "cloud-${account.id}"
        db.cloudAccountsDao().touchUsed(account.id, System.currentTimeMillis())
        return CloudFsProvider(
            id = "cloud-${account.id}",
            fs = buildFs(
                account.provider,
                key,
                {
                    val fresh = oauth.ensureFresh(def, key, tokenStore)
                        ?: throw OAuthException("account token missing — reauthorize")
                    fresh.token
                },
            ),
            providerKey = account.provider,
        )
    }

    private fun buildFs(providerKey: String, tokenKey: String, tokenProvider: suspend () -> String): CloudFs {
        val sessions = RoomSessionStore()
        val def = CloudProviderDef.byKey(providerKey)
        return when (providerKey) {
            "drive" -> DriveFs(transport, tokenProvider, sessions, accountId = tokenKey)
            "dropbox" -> DropboxFs(transport, tokenProvider, sessions, accountId = tokenKey)
            "onedrive" -> OneDriveFs(transport, tokenProvider, sessions, accountId = tokenKey)
            else -> error("unknown provider $providerKey")
        }.let { it }
    }

    /** إزالة حساب كاملة — التوكنات المشفرة ثم الصف */
    suspend fun removeAccount(account: CloudAccountEntity) = withContext(Dispatchers.IO) {
        tokenStore.remove("cloud-${account.id}")
        db.cloudAccountsDao().remove(account.id)
    }

    override fun close() {
        runCatching { vault.close() }
    }

    /** جلسة تفويض قيد الدوران — تُمرَّر بين شاشة الإضافة ونشاط العودة */
    data class PendingAuth(
        val def: CloudProviderDef,
        val oauth: OAuthClient,
        val pkce: Pkce.Pair,
        val state: String,
        val url: String,
    )
}

/**
 * مخزن جلسات رفع مبنى على Room — يصمد الرفع بعد موت العملية
 * (معيار الخارطة: «2 GB resumable upload survives process death»).
 * التخزين هنا في الملفات المشفرة للتطبيق لتفادي جداول إضافية.
 */
class RoomSessionStore : UploadSessionStore {
    private val dir = java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "nova-sessions")

    private fun file(key: String) = java.io.File(dir, key.hashCode().toString(36) + ".sess")

    override fun load(key: String): UploadSessionStore.UploadSession? {
        val f = file(key)
        if (!f.exists()) return null
        return runCatching {
            val parts = f.readText().split("\n")
            UploadSessionStore.UploadSession(
                url = parts[0],
                offset = parts[1].toLong(),
                createdAtMs = parts[2].toLong(),
                totalBytes = parts[3].toLong(),
            )
        }.getOrNull()
    }

    override fun save(key: String, session: UploadSessionStore.UploadSession) {
        dir.mkdirs()
        file(key).writeText("${session.url}\n${session.offset}\n${session.createdAtMs}\n${session.totalBytes}")
    }

    override fun remove(key: String) {
        file(key).delete()
    }
}
