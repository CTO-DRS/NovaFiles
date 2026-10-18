package com.novafiles.app.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** اختبارات سياسة إظهار اللوحتين حسب عرض النافذة */
class DualPanePolicyTest {

    @Test
    fun `phone portrait stays single pane`() {
        assertFalse(shouldUseDualPane(360))
        assertFalse(shouldUseDualPane(412))
        assertFalse(shouldUseDualPane(480))
    }

    @Test
    fun `phone landscape and tablet portrait get dual panes`() {
        assertTrue(shouldUseDualPane(600))
        assertTrue(shouldUseDualPane(780)) // هاتف بالعرض
        assertTrue(shouldUseDualPane(768)) // iPad صغير عمودياً
        assertTrue(shouldUseDualPane(1024))
        assertTrue(shouldUseDualPane(1366))
    }

    @Test
    fun `threshold is exactly at 600dp`() {
        assertFalse(shouldUseDualPane(599))
        assertTrue(shouldUseDualPane(DUAL_PANE_MIN_WIDTH_DP))
    }

    @Test
    fun `degenerate values do not crash`() {
        assertFalse(shouldUseDualPane(0))
        assertFalse(shouldUseDualPane(-1))
    }
}
