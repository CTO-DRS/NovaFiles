package com.novafiles.app.core.rules

import android.content.Context
import com.novafiles.app.data.db.NovaDb
import com.novafiles.app.data.tags.TagsRepository
import java.io.File

/**
 * v0.35.0 — تنفيذ مقبض أفعال القواعد فوق منصة Android:
 * - الوسم → محرك الوسوم TagsEngine/TagsRepository (v0.33: تعريفات فريدة
 *   بألوان حتمية + إسنادات tag_file) — يُنشأ الوسم إن لم يوجد
 * - الرفع السحابي → StorageProvider للحساب المطلوب (Drive/Dropbox/OneDrive)
 */
class RuleActionHostImpl(
    @Suppress("unused") private val context: Context,
    private val db: NovaDb,
    private val cloudAccounts: com.novafiles.app.core.cloud.CloudAccountManager,
    private val clientId: (String) -> String,
) : WatchRuleEngine.RuleActionHost {

    private val tags = TagsRepository(db)

    override suspend fun tag(path: String, tag: String) {
        // وسم قائم بنفس المفتاح (بلا حساسية حالة)؟ وإلا أنشئه بلونه الحتمي
        val key = tag.lowercase()
        val tagId = db.tagDefsDao().all().firstOrNull { it.name.lowercase() == key }?.id
            ?: tags.create(tag).id
            ?: return // فارغ بعد التطبيع أو فشل — القاعدة توثّق النجاح المنطقي الساذج
        tags.assign(tagId, listOf(path))
    }

    override suspend fun upload(file: File, accountId: Long, remoteDir: String): Boolean {
        val account = db.cloudAccountsDao().byId(accountId) ?: return false
        return try {
            val provider = cloudAccounts.providerFor(account, clientId(account.provider))
            provider.use { it.writeFrom(file, remoteDir) }
            true
        } catch (_: Exception) {
            // توكن منتهٍ/لا شبكة/رفض مزوّد — القاعدة توثّق الفشل وتكمل
            false
        }
    }
}
