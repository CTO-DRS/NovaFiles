package com.novafiles.app.core.cloud

/**
 * v0.16 — تعريفات المزوّدين السحابيين الثلاثة + مخزن التوكنات + عميل OAuth.
 *
 * كل المزوّدين عملاء عموميون (بلا سر عميل) مع PKCE S256 — وهو ما تتطلبه
 * سياسات Google الحديثة ويدعمه Dropbox وMicrosoft Graph على Android.
 *
 * معرّفات العملاء تُحلَّ من الموارد في وقت التشغيل (بناء debug يستخدم قيماً
 * اختبارية عبر BuildConfig) — النواة لا تعرف من أين تأتي.
 */

/** تعريف مزوّد سحابي — نقاط النهاية والنطاقات وحدود الاستخدام */
data class CloudProviderDef(
    /** مفتاح قصير: drive | dropbox | onedrive */
    val key: String,
    val displayName: String,
    /** صفحة التفويض */
    val authEndpoint: String,
    /** تبادل/تجديد التوكن */
    val tokenEndpoint: String,
    /** النطاقات الدنيا (scope minimization وفق الخارطة) */
    val scopes: List<String>,
    /** عنوان إعادة التوجيه المسجل — novafiles://oauth */
    val redirectUri: String,
    /** عرض حساب أونلاين للملفات فقط (مستندات Google تُصدَّر) */
    val listFolderLimit: Int = 200,
) {
    companion object {
        val DRIVE = CloudProviderDef(
            key = "drive",
            displayName = "Google Drive",
            authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth",
            tokenEndpoint = "https://oauth2.googleapis.com/token",
            scopes = listOf("https://www.googleapis.com/auth/drive.file", "openid", "email"),
            redirectUri = "com.novafiles.app:/oauth",
        )
        val DROPBOX = CloudProviderDef(
            key = "dropbox",
            displayName = "Dropbox",
            authEndpoint = "https://www.dropbox.com/oauth2/authorize",
            tokenEndpoint = "https://api.dropboxapi.com/oauth2/token",
            scopes = listOf("files.content.read", "files.content.write", "files.metadata.read"),
            redirectUri = "com.novafiles.app:/oauth",
        )
        val ONEDRIVE = CloudProviderDef(
            key = "onedrive",
            displayName = "OneDrive",
            authEndpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize",
            tokenEndpoint = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
            scopes = listOf("Files.ReadWrite", "offline_access", "User.Read"),
            redirectUri = "com.novafiles.app:/oauth",
        )

        val ALL = listOf(DRIVE, DROPBOX, ONEDRIVE)

        fun byKey(key: String): CloudProviderDef =
            ALL.firstOrNull { it.key == key } ?: error("unknown cloud provider: $key")
    }
}

/** توكن وصول حيّ — انتهاء الصلاحية بمقياس ساعة الجهاز (انظر ensureFresh) */
data class AccessToken(
    val token: String,
    /** ثواني من الآن حتى الانتهاء الفعلي للاستخدام (مع هامش سلامة) */
    val expiresAtMs: Long,
    val refreshToken: String?,
    val scope: String? = null,
) {
    fun isExpired(nowMs: Long): Boolean = nowMs >= expiresAtMs
}

/** تخزين توكنات حسابات السحابة — الإنتاج مشفّر بـ Keystore (AesGcmVault) */
interface TokenStore {
    fun save(accountId: String, token: AccessToken)
    fun load(accountId: String): AccessToken?
    fun remove(accountId: String)
}

/** نسخة ذاكرة للاختبارات */
class InMemoryTokenStore : TokenStore {
    private val map = HashMap<String, AccessToken>()
    override fun save(accountId: String, token: AccessToken) { map[accountId] = token }
    override fun load(accountId: String): AccessToken? = map[accountId]
    override fun remove(accountId: String) { map.remove(accountId) }
}

/**
 * عميل OAuth 2.0 + PKCE — بناء روابط التفويض وتبادل الرمز وتجديد التوكن.
 *
 * تصميم قابل للاختبار: كل HTTP عبر [HttpTransport]، والزمن محقون [nowMs]
 * (اختبار انتهاء الصلاحية بزمن افتراضي).
 */
class OAuthClient(
    private val transport: HttpTransport,
    private val clientId: String,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** هامش سلامة قبل الانتهاء — نعتبر التوكن منتهياً قبل 60 ثانية من موعده */
    val expirySkewMs = 60_000L

    /**
     * يبني رابط صفحة التفويض (المتصفح/Custom Tab يفتحه) — عشوائية state
     * تُعاد للمقارنة عند العودة (حماية CSRF وفق RFC 6749 §10.12).
     */
    fun buildAuthorizationUrl(def: CloudProviderDef, pkce: Pkce.Pair, state: String): String {
        val params = linkedMapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to def.redirectUri,
            "scope" to def.scopes.joinToString(" "),
            "state" to state,
            "code_challenge" to pkce.challenge,
            "code_challenge_method" to "S256",
        )
        // Google يتطلب access_type=offline لمنح refresh_token، وDropbox يعطيه تلقائياً
        if (def == CloudProviderDef.DRIVE) {
            params["access_type"] = "offline"
            params["prompt"] = "consent"
        }
        // OneDrive بحرف مسافة بين scopes، وGoogle أيضاً — كلاهما " "
        return def.authEndpoint + "?" + HttpJson.formUrlEncode(params)
    }

    /**
     * استخراج رمز التفويض وstate من رابط إعادة التوجيه — يتحقق من state مطابقة.
     * محلل query خالص (بلا android.net.Uri) ليُختبر على JVM مباشرة.
     * @return الرمز أو يرمي عند عدم التطابق/الخطأ
     */
    fun parseRedirect(def: CloudProviderDef, redirect: String, expectedState: String): String {
        val query = redirect.substringAfter('?', "")
        val params = parseQuery(query)
        val error = params["error"]
        if (error != null) throw OAuthException("authorization error: $error")
        val state = params["state"]
        if (state != expectedState) throw OAuthException("state mismatch")
        val code = params["code"] ?: throw OAuthException("missing authorization code")
        return code
    }

    /** حلل query string بترميز RFC 3986 (يدعم مفاتيح مكررة — الأخير يفوز) */
    fun parseQuery(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        if (query.isBlank()) return out
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            out[urlDecode(key)] = urlDecode(value)
        }
        return out
    }

    private fun urlDecode(s: String): String =
        java.net.URLDecoder.decode(s.replace("+", "%20"), Charsets.UTF_8)

    /**
     * تبادل رمز التفويض بزوج توكنات — RF C 6749 §4.1.3 مع PKCE verifier.
     */
    suspend fun exchangeCode(def: CloudProviderDef, code: String, verifier: String): AccessToken {
        val req = HttpJson.postForm(
            def.tokenEndpoint,
            mapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to def.redirectUri,
                "client_id" to clientId,
                "code_verifier" to verifier,
            ),
        )
        return handleTokenResponse(def, req, expectRefresh = true)
    }

    /**
     * تجديد توكن منتهٍ — grant_type=refresh_token. بعض المزوّدين (Google مع
     * prompt=consent) يعيدون refresh جديداً؛ نحافظ على القديم عند غيابه.
     */
    suspend fun refresh(def: CloudProviderDef, refreshToken: String): AccessToken {
        val req = HttpJson.postForm(
            def.tokenEndpoint,
            mapOf(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            ),
        )
        return handleTokenResponse(def, req, expectRefresh = false, fallbackRefresh = refreshToken)
    }

    /**
     * يضمن توكناً صالحاً — يُجدد من المخزن إن انتهى (مع هامش)، ويعيد النتيجة
     * محفوظة. يعيد null فقط عند غياب التوكن كلياً (حساب مفكوك).
     */
    suspend fun ensureFresh(def: CloudProviderDef, accountId: String, store: TokenStore): AccessToken? {
        val current = store.load(accountId) ?: return null
        if (!current.isExpired(nowMs())) return current
        val rt = current.refreshToken ?: throw OAuthException("no refresh token for $accountId")
        val fresh = refresh(def, rt)
        store.save(accountId, fresh)
        return fresh
    }

    private suspend fun handleTokenResponse(
        def: CloudProviderDef,
        req: HttpRequest,
        expectRefresh: Boolean,
        fallbackRefresh: String? = null,
    ): AccessToken {
        val resp = transport.execute(req)
        val bodyText = resp.text
        if (!resp.isSuccessful) {
            // 400 invalid_grant — الأشيع: رمز مستخدم/منتهٍ أو refresh مفكوك
            val err = runCatching {
                MiniJson.asString(MiniJson.parseObject(bodyText)["error"])
            }.getOrNull()
            throw OAuthException(
                "token endpoint ${resp.code}: ${err ?: bodyText.take(120)}",
                invalidGrant = err == "invalid_grant",
            )
        }
        val fields = MiniJson.parseObject(bodyText)
        val access = MiniJson.asString(fields["access_token"])
            ?: throw OAuthException("token response missing access_token")
        val expiresIn = MiniJson.asLong(fields["expires_in"]) ?: 3600L
        val refreshNew = MiniJson.asString(fields["refresh_token"]) ?: fallbackRefresh
        if (expectRefresh && refreshNew == null) {
            // نستمر — لكن بدون تجديد مستقبلي؛ Google يعطي refresh دائماً مع offline
        }
        return AccessToken(
            token = access,
            expiresAtMs = nowMs() + expiresIn * 1000 - expirySkewMs,
            refreshToken = refreshNew,
            scope = MiniJson.asString(fields["scope"]),
        )
    }
}

/** خطأ OAuth — invalidGrant يعني إعادة تفويض كاملة (لا مجرد إعادة محاولة) */
class OAuthException(message: String, val invalidGrant: Boolean = false) :
    java.io.IOException(message)
