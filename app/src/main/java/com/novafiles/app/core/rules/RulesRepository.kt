package com.novafiles.app.core.rules

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.rulesDataStore by preferencesDataStore(name = "nova_rules")

/**
 * v0.35.0 — مستودع قواعد الأتمتة: حفظ القواعد نص JSON في DataStore
 * (ترميز [RuleCodec]). القواعد تتصل بالواجهة عبر Flow حي.
 */
class RulesRepository(private val context: Context) {

    private object Keys {
        val RULES = stringPreferencesKey("rules_json")
    }

    val rules: Flow<List<WatchRule>> =
        context.rulesDataStore.data.map { prefs ->
            RuleCodec.decode(prefs[Keys.RULES].orEmpty())
        }

    suspend fun save(rules: List<WatchRule>) {
        context.rulesDataStore.edit { prefs ->
            prefs[Keys.RULES] = RuleCodec.encode(rules)
        }
    }
}
