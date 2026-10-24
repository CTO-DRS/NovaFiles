package com.novafiles.app.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.novafiles.app.core.ai.ChatMessage
import com.novafiles.app.core.ai.ChatRole
import com.novafiles.app.core.ai.TranscriptCodec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.assistantDataStore by preferencesDataStore(name = "nova_assistant")

/**
 * v0.41 — مخزن المساعد: تبديل حفظ السجل (معطّل افتراضياً) + نص السجل المرمَّز.
 *
 * خصوصية: لا يُكتب أي حرف من المحادثة على القرص إلا بعد تفعيل المستخدم
 * للسجل صراحةً من إعدادات المساعد، والملف مستثنى من أي نسخ احتياطي
 * عبر سياسة المزامنة (data_extraction_rules) عند الحاجة مستقبلاً —
 * والسجل نفسه يُمسح كلياً بزر واحد.
 */
class AssistantStore(private val context: Context) {

    private object Keys {
        val HISTORY_ENABLED = booleanPreferencesKey("history_enabled")
        val TRANSCRIPT = stringPreferencesKey("transcript")
    }

    /** هل السجل مفعّل؟ (افتراضياً لا — خصوصية أولاً) */
    val historyEnabled: Flow<Boolean> =
        context.assistantDataStore.data.map { it[Keys.HISTORY_ENABLED] ?: false }

    /** الرسائل المخزنة — فارغة دائماً عندما يكون السجل معطلاً */
    val messages: Flow<List<ChatMessage>> =
        context.assistantDataStore.data.map { prefs ->
            if (prefs[Keys.HISTORY_ENABLED] == true) {
                TranscriptCodec.decode(prefs[Keys.TRANSCRIPT].orEmpty())
            } else {
                emptyList()
            }
        }

    suspend fun setHistoryEnabled(enabled: Boolean) {
        context.assistantDataStore.edit { prefs ->
            prefs[Keys.HISTORY_ENABLED] = enabled
            if (!enabled) prefs.remove(Keys.TRANSCRIPT) // الإطفاء يمسح فوراً
        }
    }

    /** إلحاق رسالة — يعمل فقط عندما يكون السجل مفعّلاً (حماية صامتة) */
    suspend fun append(message: ChatMessage) {
        if (historyEnabled.first() != true) return
        context.assistantDataStore.edit { prefs ->
            val current = TranscriptCodec.decode(prefs[Keys.TRANSCRIPT].orEmpty())
            prefs[Keys.TRANSCRIPT] = TranscriptCodec.encode(current + message)
        }
    }

    /** مسح السجل بالكامل (يُبقي تفعيله كما هو) */
    suspend fun clearHistory() {
        context.assistantDataStore.edit { prefs ->
            prefs.remove(Keys.TRANSCRIPT)
        }
    }

    /** قراءة فورية للحالة (للواجهة الحتمية) */
    suspend fun isHistoryEnabled(): Boolean = historyEnabled.first()
}
