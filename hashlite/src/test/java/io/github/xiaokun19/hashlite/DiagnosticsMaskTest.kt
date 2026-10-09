package io.github.xiaokun19.hashlite

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Diagnostics.maskFileName 的行为钉子：诊断报告隐私增强后，
 * 报告里只保留扩展名（`报告.zip` → `***.zip`），绝不把原名带进去。
 */
class DiagnosticsMaskTest {

    @Test
    fun keepsOnlyExtension() {
        assertEquals("***.zip", Diagnostics.maskFileName("report.zip"))
    }

    @Test
    fun multiDotNameKeepsLastExtension() {
        assertEquals("***.gz", Diagnostics.maskFileName("archive.tar.gz"))
    }

    @Test
    fun chineseName() {
        assertEquals("***.xlsx", Diagnostics.maskFileName("年度报表.xlsx"))
    }

    @Test
    fun noExtensionFullyMasked() {
        assertEquals("***", Diagnostics.maskFileName("README"))
    }

    @Test
    fun dotfileFullyMasked() {
        assertEquals("***", Diagnostics.maskFileName(".gitignore"))
    }

    @Test
    fun trailingDotFullyMasked() {
        assertEquals("***", Diagnostics.maskFileName("file."))
    }

    @Test
    fun emptyNameFullyMasked() {
        assertEquals("***", Diagnostics.maskFileName(""))
    }

    @Test
    fun overlongExtensionTruncated() {
        val long = "x." + "a".repeat(40)
        assertEquals("***" + ("." + "a".repeat(40)).take(20), Diagnostics.maskFileName(long))
    }
}