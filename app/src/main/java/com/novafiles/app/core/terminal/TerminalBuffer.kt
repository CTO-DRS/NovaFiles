package com.novafiles.app.core.terminal

/**
 * مخزن شاشة الطرفية — خالص ومُختبَر 100%.
 *
 * حلقة محدودة السعة (أسطر قديمة تُطرح)، عدّاد نسخة لتحديث Compose،
 * واستخراج نص خام لقارئ الشاشة (إتاحة كاملة لناتج الأوامر).
 */
class TerminalBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    private val lines = ArrayDeque<ShellLine>()

    /** يرتفع مع كل تعديل — الواجهة تراقبه لإعادة التركيب */
    var version: Int = 0
        private set

    val size: Int get() = lines.size
    val isEmpty: Boolean get() = lines.isEmpty()

    fun append(line: ShellLine) {
        push(line)
        version++
    }

    fun appendAll(newLines: Collection<ShellLine>) {
        if (newLines.isEmpty()) return
        newLines.forEach { push(it) }
        version++
    }

    /** تطبيق ناتج تنفيذ: مسح الشاشة إن طُلب ثم إلحاق الأسطر */
    fun applyOutcome(outcome: ShellOutcome) {
        if (outcome.clearRequested) {
            lines.clear()
            version++
        }
        appendAll(outcome.lines)
    }

    fun clear() {
        if (lines.isEmpty()) return
        lines.clear()
        version++
    }

    fun snapshot(): List<ShellLine> = lines.toList()

    /** النص الخام الكامل — لقارئ الشاشة والنسخ */
    fun plainText(): String = lines.joinToString("\n") { it.text }

    private fun push(line: ShellLine) {
        lines.addLast(line)
        while (lines.size > capacity) lines.removeFirst()
    }

    companion object {
        const val DEFAULT_CAPACITY = 500
    }
}
