@file:OptIn(ExperimentalMaterial3Api::class)

package com.novafiles.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.HistoryToggleOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novafiles.app.R
import com.novafiles.app.core.ai.AiTool
import com.novafiles.app.core.ai.AssistantTurn
import com.novafiles.app.core.ai.ChatMessage
import com.novafiles.app.core.ai.ChatRole
import com.novafiles.app.core.ai.ExecutionPlan
import com.novafiles.app.core.ai.ExecutionStep
import com.novafiles.app.core.ai.RiskLevel
import com.novafiles.app.core.ai.ToolOutcome
import com.novafiles.app.novaContainer
import com.novafiles.app.ui.ios.IOSGroupCard
import com.novafiles.app.ui.ios.IOSNavBar
import com.novafiles.app.ui.ios.LocalIOSPalette
import kotlinx.coroutines.launch

/**
 * v0.41 — شاشة Nova Intelligence: مساعد محلي حتمي بأسلوب iOS.
 *
 * - رسائل مستخدم/مساعد مع بطاقات معاينة الخطة قبل أي تنفيذ.
 * - خطوات القراءة تُنفَّذ آلياً (محركات حقيقية)، والكتابة تنتظر تأكيداً صريحاً.
 * - تبديل حفظ السجل في شريط العنوان — معطّل افتراضياً (الخصوصية أولاً).
 */
@Composable
fun AssistantScreen(onBack: () -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = context.novaContainer
    val scope = rememberCoroutineScope()
    val p = LocalIOSPalette.current

    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var historyOn by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    // نموذج العرض: صف = بطاقة رسالة كاملة (نص + خطة + نتائج)
    val cards = remember { mutableStateOf(listOf<AssistantCard>()) }

    LaunchedEffect(Unit) { historyOn = container.assistantStore.isHistoryEnabled() }

    fun persist(msg: ChatMessage) {
        if (historyOn) scope.launch { container.assistantStore.append(msg) }
    }

    fun send(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        input = ""
        busy = true
        persist(ChatMessage(ChatRole.USER, text, System.currentTimeMillis()))
        cards.value += AssistantCard.user(text)

        scope.launch {
            val turn: AssistantTurn = container.assistant.handle(text)
            val reply = AssistantCard.assistant(turn)
            cards.value += reply
            persist(ChatMessage(ChatRole.ASSISTANT, turn.replyAr, System.currentTimeMillis()))

            // تنفيذ آلي لخطوات القراءة فقط — الكتابة تنتظر زر التأكيد
            if (turn.autoExecutable) {
                for (step in turn.plan!!.steps) {
                    val outcome = container.assistantExecutor.execute(step, confirmed = false)
                    reply.results += OutcomeBlock(step = step, outcome = outcome)
                }
            }
            busy = false
            listState.animateScrollToItem(cards.value.lastIndex.coerceAtLeast(0))
        }
    }

    fun confirmRuleSave(card: AssistantCard) {
        if (busy) return
        busy = true
        scope.launch {
            val plan = card.turn?.plan ?: return@launch
            val draftArgs = plan.steps.firstOrNull()?.args ?: emptyMap()
            val create = ExecutionStep(
                tool = AiTool.RULE_CREATE,
                args = draftArgs,
                summaryAr = "حفظ القاعدة بعد تأكيدك",
                summaryEn = "Save rule after your confirmation",
            )
            val outcome = container.assistantExecutor.execute(create, confirmed = true)
            card.results += OutcomeBlock(step = create, outcome = outcome)
            persist(ChatMessage(ChatRole.ASSISTANT, (outcome as? ToolOutcome.Ok)?.lines?.joinToString("\n") ?: "", System.currentTimeMillis()))
            busy = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(p.groupedBg)
            .imePadding(),
    ) {
        IOSNavBar(
            title = stringResource(R.string.ai_title),
            subtitle = stringResource(R.string.ai_subtitle),
            onBack = onBack,
            actions = {
                // تبديل حفظ السجل — الخصوصية أولاً: الإطفاء يمسح المحفوظ
                val icon = if (historyOn) Icons.Filled.History else Icons.Filled.HistoryToggleOff
                Icon(
                    icon,
                    contentDescription = stringResource(R.string.ai_history_toggle),
                    tint = if (historyOn) p.accent else p.secondaryLabel,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable {
                            val next = !historyOn
                            historyOn = next
                            scope.launch { container.assistantStore.setHistoryEnabled(next) }
                        },
                )
            },
        )

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (cards.value.isEmpty()) {
                item { EmptyState(onSuggestion = { send(it) }, suggestions = container.assistant.suggestions) }
            }
            items(cards.value, key = { it.id }) { card ->
                MessageCard(
                    card = card,
                    busy = busy,
                    onConfirmSave = { confirmRuleSave(card) },
                )
            }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            onSend = { send(input) },
            enabled = !busy,
        )
    }
}

// ═══════════ نموذج العرض ═══════════

/** بطاقة رسالة واحدة في المحادثة */
class AssistantCard private constructor(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val turn: AssistantTurn?,
) {
    /** نتائج التنفيذ الحقيقية تُلحق تدريجياً */
    val results = mutableListOf<OutcomeBlock>()

    companion object {
        private var seq = 0L
        fun user(text: String) = AssistantCard(++seq, ChatRole.USER, text, null)
        fun assistant(turn: AssistantTurn) = AssistantCard(++seq, ChatRole.ASSISTANT, turn.replyAr, turn)
    }
}

/** كتلة نتيجة خطوة واحدة */
data class OutcomeBlock(val step: ExecutionStep, val outcome: ToolOutcome)

// ═══════════ مكونات ═══════════

@Composable
private fun EmptyState(suggestions: List<String>, onSuggestion: (String) -> Unit) {
    val p = LocalIOSPalette.current
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Spacer(Modifier.size(28.dp))
        Text(
            stringResource(R.string.ai_empty_state),
            style = TextStyle(fontSize = 14.sp),
            color = p.secondaryLabel,
        )
        Spacer(Modifier.size(14.dp))
        Text(
            stringResource(R.string.ai_suggestions_title),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = p.tertiaryLabel,
        )
        Spacer(Modifier.size(8.dp))
        suggestions.take(4).forEach { s ->
            Box(
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(p.fill)
                    .clickable { onSuggestion(s) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(s, style = TextStyle(fontSize = 13.sp), color = p.accent)
            }
        }
    }
}

@Composable
private fun MessageCard(
    card: AssistantCard,
    busy: Boolean,
    onConfirmSave: () -> Unit,
) {
    val p = LocalIOSPalette.current
    val isUser = card.role == ChatRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                card.text,
                style = TextStyle(fontSize = 14.sp),
                color = if (isUser) p.accent else p.label,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isUser) p.accent.copy(alpha = 0.14f) else p.cardBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )

            // بطاقة الخطة — تظهر فقط لدور المساعد
            val plan = card.turn?.plan
            if (plan != null) {
                Spacer(Modifier.size(6.dp))
                IOSGroupCard {
                    PlanRows(plan)
                    // زر التأكيد يظهر فقط للخطط التي تتطلب تأكيداً
                    if (plan.needsConfirmation && card.results.none { it.step.tool == AiTool.RULE_CREATE }) {
                        Spacer(Modifier.size(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (busy) p.fill else p.green)
                                .clickable(enabled = !busy) { onConfirmSave() }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.ai_confirm_save_rule),
                                color = androidx.compose.ui.graphics.Color.White,
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }

            // نتائج التنفيذ الحقيقية
            card.results.forEach { block ->
                Spacer(Modifier.size(6.dp))
                IOSGroupCard {
                    val lines = when (val o = block.outcome) {
                        is ToolOutcome.Ok -> o.lines
                        is ToolOutcome.Failed -> listOf("تعذر التنفيذ: ${o.reasonAr}")
                    }
                    lines.forEach { line ->
                        Text(
                            line,
                            style = TextStyle(fontSize = 13.sp),
                            color = p.label,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlanRows(plan: ExecutionPlan) {
    val p = LocalIOSPalette.current
    plan.steps.forEachIndexed { i, step ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${i + 1}",
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                color = p.accent,
            )
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(step.summaryAr, style = TextStyle(fontSize = 14.sp), color = p.label)
                Text(
                    when (step.tool.risk) {
                        RiskLevel.READ -> stringResource(R.string.ai_risk_read)
                        RiskLevel.PLAN -> stringResource(R.string.ai_risk_plan)
                        RiskLevel.WRITE -> stringResource(R.string.ai_risk_write)
                    },
                    style = TextStyle(fontSize = 11.sp),
                    color = when (step.tool.risk) {
                        RiskLevel.READ -> p.green
                        RiskLevel.PLAN -> p.orange
                        RiskLevel.WRITE -> p.destructive
                    },
                )
            }
        }
    }
}

@Composable
private fun InputBar(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit, enabled: Boolean) {
    val p = LocalIOSPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(p.barBg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(p.fill)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    stringResource(R.string.ai_input_hint),
                    style = TextStyle(fontSize = 14.sp),
                    color = p.tertiaryLabel,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(fontSize = 14.sp, color = p.label),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(if (enabled && value.isNotBlank()) p.accent else p.fill)
                .clickable(enabled = enabled && value.isNotBlank()) { onSend() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                stringResource(R.string.ai_send),
                color = if (enabled && value.isNotBlank()) androidx.compose.ui.graphics.Color.White else p.secondaryLabel,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}
